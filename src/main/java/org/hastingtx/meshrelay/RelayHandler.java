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

        Json json       = Json.parse(body);
        String toNode   = json.getString("to");
        String fromNode = json.getString("from");
        String content  = json.getString("content");
        long   threadId = json.getLong("thread_id", -1L);

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
}
