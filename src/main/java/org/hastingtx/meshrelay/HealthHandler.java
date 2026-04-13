package org.hastingtx.meshrelay;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * GET /health — returns node status as JSON.
 * Used by peers for liveness probes and by the watchdog.
 */
public class HealthHandler implements HttpHandler {

    private final Instant        startTime;
    private final PeerConfig    config;
    private final MessagePoller poller;

    public HealthHandler(PeerConfig config, MessagePoller poller, Instant startTime) {
        this.config    = config;
        this.poller    = poller;
        this.startTime = startTime;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        long uptimeSeconds = Instant.now().getEpochSecond() - startTime.getEpochSecond();

        String lastPoll = poller.getLastPollTime().equals(java.time.Instant.EPOCH)
            ? "never" : poller.getLastPollTime().toString();

        String body = """
            {
              "node": "%s",
              "status": "ok",
              "uptime_seconds": %d,
              "peers": %s,
              "jvm_threads": %d,
              "last_poll": "%s",
              "messages_processed": %d
            }
            """.formatted(
                config.nodeName,
                uptimeSeconds,
                peerList(),
                ManagementFactory.getThreadMXBean().getThreadCount(),
                lastPoll,
                poller.getTotalProcessed()
            );

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String peerList() {
        if (config.peers.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        config.peers.keySet().forEach(p -> sb.append("\"").append(p).append("\","));
        sb.setLength(sb.length() - 1); // trim trailing comma
        sb.append("]");
        return sb.toString();
    }
}
