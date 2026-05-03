package org.hastingtx.meshrelay;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * POST /relay — claim-check message delivery.
 *
 * Flow:
 *   1. Store full message in OpenBrain → receive thread_id
 *   2. Send a tiny wake-up ping to the target peer: {from, thread_id}
 *   3. Return {thread_id, stored, wakeup_sent} to caller
 *
 * The message is durable after step 1. A failed wake-up (step 2) is NOT
 * a delivery failure — the peer will pick up the message on its next poll.
 *
 * Expected request body:
 * {
 *   "to":      "macmini",
 *   "from":    "linuxserver",
 *   "content": "full message text here"
 * }
 *
 * Response:
 * {
 *   "thread_id":    462,
 *   "stored":       true,
 *   "wakeup_sent":  true
 * }
 */
public class RelayHandler implements HttpHandler {

    private static final Logger log = Logger.getLogger(RelayHandler.class.getName());

    // Wake-up pings are tiny and on a LAN — keep timeouts short.
    private static final Duration WAKEUP_CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration WAKEUP_REQUEST_TIMEOUT = Duration.ofSeconds(5);

    // Retry parameters for the wake-up ping.
    // Message is already safe in OpenBrain, so retries are purely a latency optimisation.
    private static final int    WAKEUP_MAX_ATTEMPTS  = 3;
    private static final long[] WAKEUP_BACKOFF_MS    = {0L, 1000L, 2000L}; // before each attempt

    private final HttpClient      client;
    private final PeerConfig      config;
    private final OpenBrainStore  brain;

    /**
     * Per-thread monotonic counters used to stamp default seq_id values
     * when the sender omits seq_id. Key format: "<from_node>:<thread_id>".
     * Daemon-local; lost on restart. Acceptable because the v1.2 dedup cache
     * (issue #16) is also daemon-local.
     */
    private final ConcurrentMap<String, AtomicLong> seqCounters = new ConcurrentHashMap<>();

    public RelayHandler(HttpClient client, PeerConfig config, OpenBrainStore brain) {
        this.client = client;
        this.config = config;
        this.brain  = brain;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        String body;
        try {
            body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            sendError(exchange, 400, "Failed to read request body");
            return;
        }

        Json json        = Json.parse(body);
        String toNode    = json.getString("to");
        String fromNode  = json.getString("from");
        String content   = json.getString("content");
        String version   = json.getString("version");
        String kind      = json.getString("kind");
        long   threadId  = json.getLong("thread_id", -1L);

        if (toNode == null || toNode.isBlank()) {
            sendError(exchange, 400, "Missing required field: 'to'");
            return;
        }
        if (content == null || content.isBlank()) {
            sendError(exchange, 400, "Missing required field: 'content'");
            return;
        }
        if (fromNode == null || fromNode.isBlank()) {
            fromNode = config.nodeName; // default to self
        }
        if (version == null || version.isBlank()) {
            version = Version.VERSION; // daemon stamps its own version when caller omits it
        }

        // v1.2 wire-format fields. No behavior change in this issue (#12) —
        // fields are parsed, defaulted, validated, and stamped onto the stored
        // content. Subsequent issues consume them (dispatch #13, NO_REPLY #15,
        // dedup #16, progress #17).
        String effectiveKind = (kind != null && !kind.isBlank()) ? kind : "action";
        if (V12Fields.requiresInReplyTo(effectiveKind)
                && (json.getString("in_reply_to") == null
                    || json.getString("in_reply_to").isBlank())) {
            sendError(exchange, 400, "Missing required field 'in_reply_to' for kind=" + effectiveKind);
            return;
        }
        V12Fields v12 = V12Fields.parseFromJson(json, effectiveKind);
        if (v12.seqId() == null || v12.seqId().isBlank()) {
            v12 = v12.withSeqId(stampDefaultSeqId(fromNode, threadId));
        }

        content = stampVersionHeader(content, fromNode, version, kind, v12);

        String targetUrl = config.urlFor(toNode);
        if (targetUrl == null) {
            sendError(exchange, 404, "Unknown peer: " + toNode);
            return;
        }

        // --- Step 1: Store full message in OpenBrain ---
        // This is the only hard failure point. If OpenBrain is unreachable,
        // we cannot guarantee delivery and must return an error.
        OpenBrainStore.StoreResult stored;
        try {
            stored = brain.storeMessage(fromNode, toNode, content, threadId);
        } catch (Exception e) {
            log.severe("Failed to store message in OpenBrain: " + e.getMessage());
            sendError(exchange, 503, "message_store_unavailable");
            return;
        }

        // --- Step 2: Send wake-up ping to peer ---
        // Best-effort with retries. Failure here is NOT a delivery failure.
        boolean wakeupSent = sendWakeup(toNode, targetUrl, fromNode, stored.threadId());

        // --- Step 3: Respond to caller ---
        String response = """
            {"thread_id":%d,"message_id":%d,"stored":true,"wakeup_sent":%b}
            """.formatted(stored.threadId(), stored.messageId(), wakeupSent).trim();

        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        // 200 whether or not wake-up succeeded — message is stored either way
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /**
     * Prepend a machine-parseable version header to outgoing message content.
     * Format: [messenger v&lt;ver&gt; from &lt;node&gt;( &lt;k=v&gt;)*]\n\n&lt;body&gt;
     *
     * Kind values recognized by the receiving poller:
     *   action (default, omitted from header) — triggers processor (Claude CLI)
     *   ack, info, reply, progress, ping — non-actionable, archived without processor
     *
     * v1.2 fields (seq, ack, reply, in_reply_to, respond_by, update) are appended
     * as additional `key=value` tokens inside the brackets.
     */
    static String stampVersionHeader(String content, String fromNode, String version) {
        return stampVersionHeader(content, fromNode, version, null, null);
    }

    static String stampVersionHeader(String content, String fromNode, String version, String kind) {
        return stampVersionHeader(content, fromNode, version, kind, null);
    }

    static String stampVersionHeader(String content, String fromNode, String version,
                                     String kind, V12Fields v12) {
        if (version == null || version.isBlank())  version  = Version.VERSION;
        if (fromNode == null || fromNode.isBlank()) fromNode = "unknown";
        if (content == null) content = "";
        StringBuilder fields = new StringBuilder();
        if (kind != null && !kind.isBlank() && !"action".equals(kind)) {
            fields.append(" kind=").append(kind);
        }
        if (v12 != null) {
            if (v12.seqId() != null && !v12.seqId().isBlank()) {
                fields.append(" seq=").append(v12.seqId());
            }
            if (v12.ackPolicy() != null && !v12.ackPolicy().isBlank()) {
                fields.append(" ack=").append(v12.ackPolicy());
            }
            if (v12.replyPolicy() != null && !v12.replyPolicy().isBlank()) {
                fields.append(" reply=").append(v12.replyPolicy());
            }
            if (v12.inReplyTo() != null && !v12.inReplyTo().isBlank()) {
                fields.append(" in_reply_to=").append(v12.inReplyTo());
            }
            if (v12.respondBy() != null && !v12.respondBy().isBlank()) {
                fields.append(" respond_by=").append(v12.respondBy());
            }
            if (v12.updateIntervalSeconds() != null) {
                fields.append(" update=").append(v12.updateIntervalSeconds());
            }
        }
        return "[messenger v" + version + " from " + fromNode + fields + "]\n\n" + content;
    }

    /**
     * Matches the version header. Group 3 captures the trailing whitespace-separated
     * `key=value` tokens (kind, seq, ack, reply, in_reply_to, respond_by, update).
     *
     * Backwards-compatible: pre-v1.2 headers (no trailing fields, or only kind=...)
     * still match — group 3 is empty or contains just " kind=...". Use
     * {@link #parseHeaderFields} to split the trailer into a Map for lookup.
     */
    static final java.util.regex.Pattern HEADER_PATTERN = java.util.regex.Pattern.compile(
        "^\\[messenger v(\\S+) from (\\S+?)((?:\\s+\\w+=\\S+)*)\\]"
    );

    /**
     * Split the trailing header fields (group 3 of HEADER_PATTERN) into a
     * key→value map. Order-independent, so future additions don't reshuffle
     * group indices.
     */
    static Map<String, String> parseHeaderFields(String trailer) {
        if (trailer == null || trailer.isBlank()) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        for (String token : trailer.trim().split("\\s+")) {
            if (token.isEmpty()) continue;
            int eq = token.indexOf('=');
            if (eq > 0 && eq < token.length() - 1) {
                result.put(token.substring(0, eq), token.substring(eq + 1));
            }
        }
        return result;
    }

    /**
     * Extract the kind from a stored content header. Returns "action" for messages
     * without a kind (including messages from old daemons that don't write the
     * kind suffix). Returns the parsed value otherwise.
     *
     * <p>Recognized v1.2 kinds (see docs/protocol-v1.2.md § "kind enum"):
     * <ul>
     *   <li>{@code action}   — runs the processor (Claude CLI, etc.). Default when
     *       the header omits the kind field.</li>
     *   <li>{@code reply}    — response payload to a prior {@code REQ_ACK}. Carries
     *       {@code in_reply_to=&lt;seq_id&gt;}. Poller archives without invoking
     *       the processor.</li>
     *   <li>{@code ack}      — empty-payload delivery confirmation when a processor
     *       produced no output. Archived without processor invocation.</li>
     *   <li>{@code progress} — periodic liveness beat from a working processor.
     *       Excluded from {@code MAX_TURNS_PER_THREAD}. Archived without
     *       processor invocation.</li>
     *   <li>{@code info}     — broadcasts and one-way notifications. Archived.</li>
     *   <li>{@code ping}     — daemon-handled liveness probe. Daemon auto-responds
     *       with a {@code kind=reply payload="pong"}. Never reaches Claude.</li>
     * </ul>
     *
     * <p>Behavior is unchanged from the v1.1.x implementation — this method just
     * returns whatever string is in the header; dispatch decisions live in
     * {@link MessagePoller}.
     */
    public static String extractKind(String content) {
        return extractHeaderField(content, "kind", "action");
    }

    /**
     * Extract a named header field from stored content, with a default value if
     * absent. Used by extractKind and (in subsequent v1.2 issues) by extractors
     * for seq, in_reply_to, etc.
     */
    public static String extractHeaderField(String content, String fieldName, String defaultValue) {
        if (content == null) return defaultValue;
        var m = HEADER_PATTERN.matcher(content);
        if (!m.find()) return defaultValue;
        String value = parseHeaderFields(m.group(3)).get(fieldName);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    /**
     * Generate a daemon-stamped seq_id when the sender omits one.
     * Format: <from>:<thread_id>:<counter>, with counter monotonic per (from, thread).
     */
    String stampDefaultSeqId(String fromNode, long threadId) {
        String key = fromNode + ":" + threadId;
        long n = seqCounters.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
        return fromNode + ":" + threadId + ":" + n;
    }

    /**
     * Strip the version header from stored content, returning just the body.
     * Returns the input unchanged when no header is present (legacy messages).
     */
    public static String extractBody(String content) {
        if (content == null) return "";
        var m = HEADER_PATTERN.matcher(content);
        if (!m.find()) return content;
        // Skip past the matched header and any immediately-following whitespace/newlines
        int start = m.end();
        while (start < content.length() && Character.isWhitespace(content.charAt(start))) {
            start++;
        }
        return content.substring(start);
    }

    /**
     * Send a wake-up ping to the target peer with retries.
     *
     * The wake-up body is intentionally minimal — just enough for the peer
     * to know where to look in OpenBrain.
     *
     * Returns true if any attempt succeeded, false if all failed.
     */
    private boolean sendWakeup(String peerName, String peerBaseUrl,
                                String fromNode, long threadId) {
        String endpoint = peerBaseUrl + "/wake";
        String ping = """
            {"from":"%s","thread_id":%d}
            """.formatted(fromNode, threadId).trim();

        for (int attempt = 0; attempt < WAKEUP_MAX_ATTEMPTS; attempt++) {
            if (WAKEUP_BACKOFF_MS[attempt] > 0) {
                try {
                    Thread.sleep(WAKEUP_BACKOFF_MS[attempt]); // virtual thread parks here
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(WAKEUP_REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(ping))
                    .build();

                HttpResponse<Void> response = client.send(
                    request, HttpResponse.BodyHandlers.discarding());

                if (response.statusCode() < 300) {
                    log.info("Wake-up sent to " + peerName
                        + " thread_id=" + threadId
                        + (attempt > 0 ? " (attempt " + (attempt + 1) + ")" : ""));
                    return true;
                }

                log.warning("Wake-up to " + peerName + " returned HTTP "
                    + response.statusCode() + " (attempt " + (attempt + 1) + ")");

            } catch (Exception e) {
                log.warning("Wake-up to " + peerName + " failed: "
                    + e.getMessage() + " (attempt " + (attempt + 1) + ")");
            }
        }

        log.warning("Wake-up to " + peerName
            + " failed after " + WAKEUP_MAX_ATTEMPTS + " attempts — "
            + "message thread_id=" + threadId + " safe in OpenBrain, peer will catch up");
        return false;
    }

    private void sendError(HttpExchange exchange, int code, String message) throws IOException {
        String b = "{\"error\":\"" + message + "\"}";
        byte[] bytes = b.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // v1.2 wire-format fields
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * v1.2 fields from the inbound /relay JSON body. All fields are optional on
     * the wire; defaults for ack_policy and reply_policy are applied here based
     * on the message kind (per docs/protocol-v1.2.md "Wire format" table).
     *
     * No behavior change in this issue — fields are read into the in-flight
     * message, defaulted, validated, and stamped onto the stored content header.
     * Subsequent issues consume them: dispatch (#13), NO_REPLY (#15), dedup (#16),
     * progress (#17).
     */
    public record V12Fields(
            String  seqId,
            String  ackPolicy,
            String  replyPolicy,
            String  respondBy,
            Integer updateIntervalSeconds,
            String  inReplyTo
    ) {
        /**
         * Parse v1.2 fields from a /relay JSON body and apply per-kind defaults
         * for ack_policy and reply_policy. seq_id is left null when absent —
         * the caller stamps it from a per-thread counter so the value is correct
         * relative to daemon state.
         */
        public static V12Fields parseFromJson(Json json, String effectiveKind) {
            String seqId       = json.getString("seq_id");
            String ackPolicy   = json.getString("ack_policy");
            String replyPolicy = json.getString("reply_policy");
            String respondBy   = json.getString("respond_by");
            String inReplyTo   = json.getString("in_reply_to");

            // update_interval_seconds is optional — the daemon clamps to [30, 120]
            // when consuming it (issue #17). Here we just record the raw int.
            Integer updateInterval = json.has("update_interval_seconds")
                ? json.getInt("update_interval_seconds", 0)
                : null;

            if (ackPolicy == null || ackPolicy.isBlank())     ackPolicy   = defaultAckPolicy(effectiveKind);
            if (replyPolicy == null || replyPolicy.isBlank()) replyPolicy = defaultReplyPolicy(effectiveKind);

            return new V12Fields(seqId, ackPolicy, replyPolicy, respondBy, updateInterval, inReplyTo);
        }

        /** Replace seq_id (used to stamp the daemon-generated default). */
        public V12Fields withSeqId(String newSeqId) {
            return new V12Fields(newSeqId, ackPolicy, replyPolicy, respondBy,
                                  updateIntervalSeconds, inReplyTo);
        }

        /**
         * Default ack_policy per spec table:
         *   action, ping             → REQ_ACK
         *   info, reply, ack, progress → NO_ACK
         */
        public static String defaultAckPolicy(String kind) {
            return switch (kind) {
                case "action", "ping"                       -> "REQ_ACK";
                case "info", "reply", "ack", "progress"     -> "NO_ACK";
                default                                     -> "REQ_ACK"; // unknown ⇒ treat like action
            };
        }

        /**
         * Default reply_policy per spec table:
         *   action, ping                        → REPLY
         *   info, reply, ack, progress          → NO_REPLY
         */
        public static String defaultReplyPolicy(String kind) {
            return switch (kind) {
                case "action", "ping"                       -> "REPLY";
                case "info", "reply", "ack", "progress"     -> "NO_REPLY";
                default                                     -> "REPLY";
            };
        }

        /**
         * True for kinds that require in_reply_to per the spec:
         * reply, ack, and progress all reference a prior message's seq_id.
         */
        public static boolean requiresInReplyTo(String kind) {
            return "reply".equals(kind) || "ack".equals(kind) || "progress".equals(kind);
        }
    }
}
