package org.hastingtx.meshrelay;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * MessageProcessor that forwards incoming messages to Richard via Telegram,
 * waits for his reply, and relays the reply back to the original sender.
 *
 * Uses the ask-user TCP server in telegram-claude-bot's bot.py
 * (default host:port = localhost:7802). Sends JSON requests with the
 * "mesh": true flag to opt into:
 *   - quiet hours (22:00-08:00 America/Chicago — held until 08:00)
 *   - FIFO serialization (one ask in front of Richard at a time)
 *   - long timeouts (up to ASK_MAX_TIMEOUT, default 8h on the bot side)
 *
 * The poller calls process() serially in a single thread, which means
 * concurrent mesh asks are already serialized at the messenger layer
 * before they ever hit the FIFO lock on the bot side.
 *
 * Configuration (env vars or .env file):
 *   ASK_HOST         — bot ask-user host  (default: 127.0.0.1)
 *   ASK_PORT         — bot ask-user port  (default: 7802)
 *   ASK_TIMEOUT      — seconds to wait for Richard's reply (default: 14400 / 4h)
 *
 * Activation in MeshRelay.main(): when config.processor == "telegram-relay".
 */
public class TelegramProcessor implements MessageProcessor {

    private static final Logger log = Logger.getLogger(TelegramProcessor.class.getName());

    private static final String DEFAULT_ASK_HOST = "127.0.0.1";
    private static final int    DEFAULT_ASK_PORT = 7802;
    private static final int    DEFAULT_ASK_TIMEOUT_SECONDS = 14400; // 4h
    /** Socket SO_TIMEOUT — generous enough to survive 10h quiet-hours hold + 8h reply wait. */
    private static final int    SOCKET_READ_TIMEOUT_MS = 24 * 60 * 60 * 1000;
    private static final Duration RELAY_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient http;
    private final PeerConfig config;
    private final String     askHost;
    private final int        askPort;
    private final int        askTimeoutSeconds;
    private final String     relayUrl;
    private final DedupCache dedupCache;

    private TelegramProcessor(HttpClient http, PeerConfig config,
                              String askHost, int askPort, int askTimeoutSeconds,
                              DedupCache dedupCache) {
        this.http              = http;
        this.config            = config;
        this.askHost           = askHost;
        this.askPort           = askPort;
        this.askTimeoutSeconds = askTimeoutSeconds;
        this.relayUrl          = "http://localhost:" + config.listenPort + "/relay";
        this.dedupCache        = dedupCache;
    }

    @Override
    public String name() { return "telegram-relay"; }

    /**
     * Build a TelegramProcessor if the bot's ask-user TCP server is reachable.
     * Returns null otherwise so MeshRelay.main() can fail loudly.
     */
    public static MessageProcessor create(HttpClient http, PeerConfig config,
                                          DedupCache dedupCache) {
        String askHost = envOr("ASK_HOST", DEFAULT_ASK_HOST);
        int askPort = parseIntOr("ASK_PORT", DEFAULT_ASK_PORT);
        int askTimeoutSeconds = parseIntOr("ASK_TIMEOUT", DEFAULT_ASK_TIMEOUT_SECONDS);

        // Quick liveness probe — open and immediately close a TCP connection.
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(askHost, askPort), 3000);
        } catch (Exception e) {
            log.warning("Telegram ask-user server unreachable at "
                + askHost + ":" + askPort + " — " + e.getMessage());
            return null;
        }

        log.info("TelegramProcessor active — ask-user=" + askHost + ":" + askPort
            + " ask_timeout=" + askTimeoutSeconds + "s node=" + config.nodeName);
        return new TelegramProcessor(http, config, askHost, askPort,
            askTimeoutSeconds, dedupCache);
    }

    /** Backwards-compatible factory — no dedup cache wiring (used in tests). */
    public static MessageProcessor create(HttpClient http, PeerConfig config) {
        return create(http, config, null);
    }

    @Override
    public void process(OpenBrainStore.PendingMessage msg) throws Exception {
        log.info("Processing message via Telegram — thread_id=" + msg.threadId()
            + " from=" + msg.fromNode());

        // Prefix the question so Richard knows the routing context.
        String prefixed = "[mesh: from " + msg.fromNode()
            + " on thread " + msg.threadId() + "]\n\n"
            + msg.content();

        String reply = askRichard(prefixed, msg.fromNode());

        // v1.2 auto-response shape (issue #15): kind=reply, NO_REPLY,
        // in_reply_to=<inbound seq_id>. Mirrors GemmaProcessor / ClaudeCliProcessor.
        String inReplyTo = RelayHandler.extractHeaderField(msg.content(), "seq", null);
        if (inReplyTo == null || inReplyTo.isBlank()) {
            inReplyTo = msg.fromNode() + ":" + msg.threadId() + ":0";
        }

        sendReply(msg.fromNode(), reply, msg.threadId(), inReplyTo);

        log.info("Reply sent to " + msg.fromNode() + " thread_id=" + msg.threadId()
            + " in_reply_to=" + inReplyTo);

        // v1.2 dedup cache (issue #16). Record the actual outbound reply so a
        // duplicate inbound replays this response instead of re-asking Richard.
        String originalSeq = RelayHandler.extractHeaderField(msg.content(), "seq", null);
        if (dedupCache != null && originalSeq != null && !originalSeq.isBlank()) {
            dedupCache.put(
                new DedupCache.DedupKey(msg.fromNode(), msg.threadId(), originalSeq),
                new DedupCache.CachedResponse("reply", reply, inReplyTo,
                    java.time.Instant.now()));
        }
    }

    /**
     * Open a TCP connection to the bot's ask-user server, send the JSON request,
     * wait for the response line, and return the reply text. Throws on error.
     */
    private String askRichard(String question, String senderNode) throws Exception {
        String request = "{"
            + "\"question\":\"" + Json.escape(question) + "\","
            + "\"sender\":\""   + Json.escape(senderNode) + "\","
            + "\"timeout\":"    + askTimeoutSeconds + ","
            + "\"mesh\":true"
            + "}\n";

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(askHost, askPort), 5000);
            socket.setSoTimeout(SOCKET_READ_TIMEOUT_MS);

            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.UTF_8));
            out.flush();

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String responseLine = reader.readLine();
            if (responseLine == null) {
                throw new Exception("Bot ask-user server closed connection without responding");
            }

            Json parsed = Json.parse(responseLine);
            String reply = parsed.getString("reply");
            if (reply != null) return reply;

            // Error path — bot returns {"error": "...", "message": "..."}
            String error = parsed.getString("error");
            String message = parsed.getString("message");
            throw new Exception("Bot ask-user error: " + error
                + (message != null ? " (" + message + ")" : ""));
        }
    }

    private void sendReply(String toNode, String replyContent, long threadId, String inReplyTo) throws Exception {
        // kind="action" (issue #23): the asking agent's session has already
        // exited by the time Richard answers. kind=reply would be filtered by
        // MessagePoller's non-action skip; kind=action invokes the processor
        // so the agent can read the thread for context and act on the answer.
        //
        // reply_policy is intentionally omitted (defaults to REPLY). Setting
        // NO_REPLY would trigger MessagePoller.isAutoResponseSuppressed and
        // skip the processor entirely — defeating the whole point of issue
        // #23. The risk of an outbound reply storm is bounded because:
        //   1. The system prompt teaches Claude not to ack richard's answers.
        //   2. ClaudeCliProcessor has a v1.1.6 ack-shaped output filter that
        //      drops trivial acks before they're relayed.
        //   3. If a substantive reply does come through, it's actually
        //      meaningful and worth surfacing on Telegram.
        String body = "{\"to\":\"" + toNode + "\","
            + "\"from\":\"" + config.nodeName + "\","
            + "\"content\":\"" + Json.escape(replyContent) + "\","
            + "\"kind\":\"action\","
            + "\"in_reply_to\":\"" + Json.escape(inReplyTo) + "\","
            + "\"thread_id\":" + threadId + "}";

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(relayUrl))
            .header("Content-Type", "application/json")
            .timeout(RELAY_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new Exception("Local relay returned HTTP " + response.statusCode());
        }
    }

    private static String envOr(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private static int parseIntOr(String key, int fallback) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) return fallback;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            log.warning("Invalid integer for " + key + "=\"" + v + "\" — using " + fallback);
            return fallback;
        }
    }
}
