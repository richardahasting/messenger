package org.hastingtx.meshrelay;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
 * Single-instance guarantee:
 *   On startup, acquires an exclusive OS-level lock on ~/.messenger.lock.
 *   If the lock is already held (another instance is running), logs and exits
 *   immediately. The OS releases the lock automatically on JVM exit — even
 *   on crash or kill -9 — so no stale lock files ever need cleanup.
 *
 * Config: reads ~/projects/messenger/config.json (bootstrap only).
 * Usage:  java -jar messenger.jar [path/to/config.json]
 */
public class MeshRelay {

    private static final Logger log = Logger.getLogger(MeshRelay.class.getName());

    public static void main(String[] args) throws Exception {
        // ── Single-instance lock ──────────────────────────────────────────────
        // Must be first — before config load, before anything else.
        // Keep the FileChannel open for the entire process lifetime; closing it
        // releases the lock. The shutdown hook closes it on clean exit; the OS
        // reclaims it on crash or kill.
        String configArg = args.length > 0 ? Path.of(args[0]).getFileName().toString() : "config.json";
        String lockSuffix = configArg.replaceAll("^config-?", "").replaceAll("\\.json$", "");
        String lockName = lockSuffix.isEmpty() ? ".messenger.lock" : ".messenger-" + lockSuffix + ".lock";
        Path lockFile = Path.of(System.getProperty("user.home"), lockName);
        FileChannel lockChannel = FileChannel.open(lockFile,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock instanceLock = lockChannel.tryLock();

        if (instanceLock == null) {
            log.severe("messenger is already running on this machine "
                + "(lock held: " + lockFile + ") — exiting.");
            lockChannel.close();
            System.exit(1);
        }

        log.info("Instance lock acquired: " + lockFile);

        // ── Config ────────────────────────────────────────────────────────────
        Path configPath = args.length > 0
            ? Path.of(args[0])
            : Path.of(System.getProperty("user.home"), "projects/messenger/config.json");

        PeerConfig config = PeerConfig.load(configPath);
        log.info("Loaded config: " + config);

        // ── HTTP client ───────────────────────────────────────────────────────
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

        // ── OpenBrain + poller ────────────────────────────────────────────────
        OpenBrainStore brain = new OpenBrainStore(client, config);

        // Processor selection — config.processor overrides auto-detection:
        //   "gemma"     → GemmaProcessor only (dedicated Ollama agent)
        //   "claude-cli"→ ClaudeCliProcessor only
        //   null/absent → auto priority: ClaudeCliProcessor → GemmaProcessor → logging
        MessageProcessor processor;
        if ("gemma".equals(config.processor)) {
            log.info("Processor forced to Gemma via config");
            processor = GemmaProcessor.create(client, config);
            if (processor == null) {
                log.severe("processor=gemma requested but Ollama is unreachable — exiting.");
                System.exit(1);
            }
        } else if ("claude-cli".equals(config.processor)) {
            log.info("Processor forced to Claude CLI via config");
            processor = ClaudeCliProcessor.create(client, config, brain);
            if (processor == null) {
                log.severe("processor=claude-cli requested but claude binary not found — exiting.");
                System.exit(1);
            }
        } else {
            // Auto priority:
            //   1. ClaudeCliProcessor — claude -p CLI, uses subscription (free), full tool use
            //   2. GemmaProcessor     — local Ollama, works without internet
            //   3. logging()          — safe no-op fallback
            processor = ClaudeCliProcessor.create(client, config, brain);
            if (processor == null) processor = GemmaProcessor.create(client, config);
            if (processor == null) processor = MessageProcessor.logging();
        }

        MessagePoller poller = new MessagePoller(config, brain, processor);
        poller.startInBackground();

        // ── HTTP server ───────────────────────────────────────────────────────
        HttpServer server = HttpServer.create(
            new InetSocketAddress(config.listenPort), /*backlog=*/ 64);

        // Virtual thread per request — Java 21 Project Loom.
        // Blocking I/O (OpenBrain writes, peer wake-ups) parks the virtual
        // thread rather than blocking a platform thread. No async needed.
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/relay",     new RelayHandler(client, config, brain));
        server.createContext("/broadcast", new BroadcastHandler(client, config, brain));
        server.createContext("/wake",      new WakeHandler(config, poller));
        server.createContext("/health",    new HealthHandler(config, poller, java.time.Instant.now()));
        server.createContext("/ping", exchange -> {
            byte[] pong = "pong".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, pong.length);
            exchange.getResponseBody().write(pong);
            exchange.close();
        });

        // ── Shutdown hook ─────────────────────────────────────────────────────
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down — draining in-flight requests (max 5s)...");
            server.stop(5);
            try { instanceLock.release(); lockChannel.close(); } catch (IOException ignored) {}
            log.info("Shutdown complete.");
        }));

        server.start();
        log.info("messenger started — node='" + config.nodeName
            + "' port=" + config.listenPort
            + " openBrain=" + config.openBrainUrl
            + " config_source=" + config.source);
    }
}
