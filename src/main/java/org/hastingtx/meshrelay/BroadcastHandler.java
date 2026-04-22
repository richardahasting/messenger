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
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * POST /broadcast
 *
 * Fans out a message to all known peers concurrently.
 * Does NOT send to self. Does NOT require all peers to succeed —
 * partial delivery is reported in the response.
 *
 * Response JSON:
 * {
 *   "delivered": ["macmini"],
 *   "failed":    ["macbook-air"]
 * }
 *
 * Uses virtual threads for concurrent fan-out: all peers are contacted
 * simultaneously, the slowest peer determines total latency.
 */
public class BroadcastHandler implements HttpHandler {

    private static final Logger log = Logger.getLogger(BroadcastHandler.class.getName());
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient     client;
    private final PeerConfig     config;
    private final OpenBrainStore brain;

    public BroadcastHandler(HttpClient client, PeerConfig config, OpenBrainStore brain) {
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

        String body = new String(
            exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        Json json       = Json.parse(body);
        String fromNode = json.getString("from");
        String content  = json.getString("content");
        String version  = json.getString("version");
        if (fromNode == null) fromNode = config.nodeName;
        if (version == null || version.isBlank()) version = Version.VERSION;

        // Store the broadcast in OpenBrain once, tagged to:all
        // This is the only hard failure point — same as RelayHandler.
        if (content == null || content.isBlank()) {
            sendError(exchange, 400, "Missing required field: 'content'");
            return;
        }
        content = RelayHandler.stampVersionHeader(content, fromNode, version);
        OpenBrainStore.StoreResult stored;
        try {
            stored = brain.storeMessage(fromNode, "all", content);
        } catch (Exception e) {
            log.severe("Failed to store broadcast in OpenBrain: " + e.getMessage());
            sendError(exchange, 503, "message_store_unavailable");
            return;
        }
        final long finalThreadId = stored.threadId();
        final String finalFrom  = fromNode;

        // Fan out wake-up pings to all peers concurrently using virtual threads
        List<String> delivered = new CopyOnWriteArrayList<>();
        List<String> failed    = new CopyOnWriteArrayList<>();

        List<Thread> threads = new ArrayList<>();
        for (Map.Entry<String, String> peer : config.peers.entrySet()) {
            String peerName = peer.getKey();
            String peerUrl  = peer.getValue() + "/wake";

            // Send wake-up ping (not full content — content is in OpenBrain)
            String wakeBody = "{\"from\":\"" + finalFrom + "\",\"thread_id\":" + finalThreadId + "}";
            Thread t = Thread.ofVirtual().start(() -> {
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(peerUrl))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(wakeBody))
                        .build();
                    HttpResponse<Void> resp = client.send(
                        req, HttpResponse.BodyHandlers.discarding());
                    if (resp.statusCode() < 300) {
                        delivered.add(peerName);
                        log.info("Broadcast wake-up delivered to " + peerName);
                    } else {
                        failed.add(peerName);
                        log.warning("Broadcast wake-up to " + peerName + " returned HTTP " + resp.statusCode());
                    }
                } catch (Exception e) {
                    failed.add(peerName);
                    log.warning("Broadcast wake-up to " + peerName + " failed: " + e.getMessage());
                }
            });
            threads.add(t);
        }

        // Wait for all concurrent deliveries to complete
        for (Thread t : threads) {
            try { t.join(); } catch (InterruptedException ignored) {}
        }

        String responseBody = """
            {"thread_id":%d,"delivered":%s,"failed":%s}
            """.formatted(finalThreadId, toJsonArray(delivered), toJsonArray(failed)).trim();

        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String toJsonArray(List<String> items) {
        if (items.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        items.forEach(i -> sb.append("\"").append(i).append("\","));
        sb.setLength(sb.length() - 1);
        sb.append("]");
        return sb.toString();
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
