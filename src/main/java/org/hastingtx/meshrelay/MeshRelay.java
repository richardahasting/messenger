package org.hastingtx.meshrelay;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * agent-mesh-relay — claim-check message relay daemon.
 *
 * Architecture:
 *   - Full message content lives in OpenBrain (PostgreSQL) permanently.
 *   - This daemon carries only thread IDs and wake-up pings.
 *   - A failed wake-up is NOT a delivery failure: peer catches up via OpenBrain poll.
 *
 * Endpoints:
 *   POST /relay     — store message in OpenBrain, send wake-up to peer
 *   POST /broadcast — store broadcast in OpenBrain, wake all peers
 *   POST /wake      — receive wake-up from a peer (acknowledge, log)
 *   GET  /health    — node status JSON
 *   GET  /ping      — liveness probe
 *
 * Config: reads the existing agent-mesh config.json (same file as Python daemon).
 * Usage:  java -jar agent-mesh-relay.jar [path/to/config.json]
 */
public class MeshRelay {

    private static final Logger log = Logger.getLogger(MeshRelay.class.getName());

    public static void main(String[] args) throws Exception {
        Path configPath = args.length > 0
            ? Path.of(args[0])
            : Path.of(System.getProperty("user.home"), "projects/agent-mesh/config.json");

        PeerConfig config = PeerConfig.load(configPath);
        log.info("Loaded config: " + config);

        // Shared outbound HTTP client — thread-safe, connection-pooled
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

        OpenBrainStore brain = new OpenBrainStore(client, config);

        // Background poller — queries OpenBrain every 10 minutes.
        // Also triggered immediately by incoming wake-up pings.
        // Swap MessageProcessor.logging() for a real Claude/Gemma4 processor later.
        MessagePoller poller = new MessagePoller(config, brain, MessageProcessor.logging());
        poller.startInBackground();

        HttpServer server = HttpServer.create(
            new InetSocketAddress(config.listenPort), /*backlog=*/ 64);

        // Virtual thread per request — Java 21 Project Loom.
        // Blocking I/O (OpenBrain writes, peer wake-ups) parks the virtual
        // thread rather than blocking a platform thread. No async needed.
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/relay",     new RelayHandler(client, config, brain));
        server.createContext("/broadcast", new BroadcastHandler(client, config, brain));
        server.createContext("/wake",      new WakeHandler(config, poller));
        server.createContext("/health",    new HealthHandler(config, poller));
        server.createContext("/ping", exchange -> {
            byte[] pong = "pong".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, pong.length);
            exchange.getResponseBody().write(pong);
            exchange.close();
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down — draining in-flight requests (max 5s)...");
            server.stop(5);
            log.info("Shutdown complete.");
        }));

        server.start();
        log.info("agent-mesh-relay started — node='" + config.nodeName
            + "' port=" + config.listenPort
            + " openBrain=" + config.openBrainUrl);
    }
}
