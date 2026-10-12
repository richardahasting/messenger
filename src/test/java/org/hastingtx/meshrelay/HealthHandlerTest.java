package org.hastingtx.meshrelay;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * /health must say where the config came from (issue #35): an instance running on
 * cached or bootstrap config answers 200 like a healthy one, so the source has to be
 * visible to whatever is checking.
 */
class HealthHandlerTest {

    @Test
    @Timeout(10)
    void healthReportsConfigSource() throws Exception {
        PeerConfig cfg = PeerConfig.parseConfig(
            "{\"listen_port\":13007}", "macbook-air", "http://brain", "k", "cache");
        MessagePoller poller = new MessagePoller(
            cfg, new PingHandlerTest.RecordingStore(cfg), new PingHandlerTest.CountingProcessor());

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health",
            new HealthHandler(cfg, poller, Instant.now(), new PingHandlerTest.CountingProcessor()));
        server.start();
        try {
            int port = server.getAddress().getPort();
            HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health")).build(),
                HttpResponse.BodyHandlers.ofString());

            assertEquals(200, resp.statusCode());
            Json health = Json.parse(resp.body());
            assertEquals("macbook-air", health.getString("node"));
            assertEquals("cache", health.getString("config_source"));
        } finally {
            server.stop(0);
        }
    }
}
