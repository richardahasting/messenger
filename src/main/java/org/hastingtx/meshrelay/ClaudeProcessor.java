package org.hastingtx.meshrelay;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MessageProcessor implementation that forwards incoming messages to the
 * Claude API and sends the response back to the sender via the local relay.
 *
 * Flow:
 *   1. Receive PendingMessage (from, content, thread_id)
 *   2. POST to Anthropic API — system prompt gives Claude its node identity
 *   3. POST reply to localhost:13007/relay  → stored in OpenBrain, wake-up sent
 *
 * Required env var: ANTHROPIC_API_KEY
 *
 * Falls back to logging() if the API key is absent so the daemon still starts.
 * API key can also be set in the .env file loaded by the systemd/launchd unit.
 */
public class ClaudeProcessor implements MessageProcessor {

    private static final Logger log = Logger.getLogger(ClaudeProcessor.class.getName());

    private static final String CLAUDE_API_URL  = "https://api.anthropic.com/v1/messages";
    private static final String CLAUDE_MODEL    = "claude-sonnet-4-6";
    private static final int    MAX_TOKENS      = 1024;
    private static final Duration API_TIMEOUT   = Duration.ofMinutes(10);
    private static final Duration RELAY_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient  http;
    private final PeerConfig  config;
    private final String      apiKey;
    private final String      relayUrl;   // local messenger relay endpoint

    private ClaudeProcessor(HttpClient http, PeerConfig config, String apiKey) {
        this.http     = http;
        this.config   = config;
        this.apiKey   = apiKey;
        this.relayUrl = "http://localhost:" + config.listenPort + "/relay";
    }

    /**
     * Build a ClaudeProcessor if ANTHROPIC_API_KEY is available,
     * otherwise return the logging() fallback and warn loudly.
     */
    public static MessageProcessor create(HttpClient http, PeerConfig config) {
        String key = System.getenv("ANTHROPIC_API_KEY");
        if (key == null || key.isBlank()) {
            log.warning("ANTHROPIC_API_KEY not set — falling back to logging processor. "
                + "Set it in .env or environment to enable Claude processing.");
            return MessageProcessor.logging();
        }
        log.info("ClaudeProcessor active — model=" + CLAUDE_MODEL + " node=" + config.nodeName);
        return new ClaudeProcessor(http, config, key);
    }

    @Override
    public void process(OpenBrainStore.PendingMessage msg) throws Exception {
        log.info("Processing message via Claude — thread_id=" + msg.threadId()
            + " from=" + msg.fromNode());

        String reply = callClaude(msg.fromNode(), msg.content());
        sendReply(msg.fromNode(), reply, msg.threadId());

        log.info("Reply sent to " + msg.fromNode() + " thread_id=" + msg.threadId());
    }

    /**
     * Call the Claude API with the incoming message as the user turn.
     * The system prompt establishes the agent's identity and purpose.
     */
    private String callClaude(String fromNode, String userContent) throws Exception {
        String systemPrompt = buildSystemPrompt(fromNode);
        String requestBody  = buildApiRequest(systemPrompt, userContent);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(CLAUDE_API_URL))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .timeout(API_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new Exception("Claude API returned HTTP " + response.statusCode()
                + ": " + response.body().substring(0, Math.min(200, response.body().length())));
        }

        return extractReplyText(response.body());
    }

    /**
     * Send the Claude reply back to the original sender via the local relay.
     * The reply is stored in OpenBrain and a wake-up ping is sent — fully durable.
     */
    private void sendReply(String toNode, String replyContent, long threadId) throws Exception {
        String escaped = replyContent
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");

        // Include thread_id so replies are grouped into the same conversation
        String body = "{\"to\":\"" + toNode + "\","
            + "\"from\":\"" + config.nodeName + "\","
            + "\"content\":\"" + escaped + "\","
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

    private String buildSystemPrompt(String fromNode) {
        return "You are the " + config.nodeName + " agent in a distributed multi-agent system. "
            + "You are receiving a message from the " + fromNode + " agent. "
            + "Respond helpfully and concisely — your reply will be delivered back to "
            + fromNode + " as a messenger message. "
            + "If asked to run a shell command, execute it mentally and include the result. "
            + "Keep replies focused and brief.";
    }

    private String buildApiRequest(String systemPrompt, String userContent) {
        String escapedSystem = jsonEscape(systemPrompt);
        String escapedUser   = jsonEscape(userContent);
        return "{\"model\":\"" + CLAUDE_MODEL + "\","
            + "\"max_tokens\":" + MAX_TOKENS + ","
            + "\"system\":\"" + escapedSystem + "\","
            + "\"messages\":[{\"role\":\"user\",\"content\":\"" + escapedUser + "\"}]}";
    }

    /** Extract the text content from the Anthropic API response JSON. */
    private String extractReplyText(String json) {
        // Response: {"content":[{"type":"text","text":"..."}],...}
        Matcher m = Pattern.compile("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        if (!m.find()) throw new IllegalStateException("No text in Claude API response");
        return m.group(1)
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\r", "\r")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\");
    }

    private String jsonEscape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
