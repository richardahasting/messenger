package org.hastingtx.meshrelay;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import java.util.stream.Stream;

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
        // ── Version banner ────────────────────────────────────────────────────
        // First line in the log so `tail -1` of an old log reveals the running
        // version without needing to hit /health. Also makes mixed-version
        // rollouts obvious when grepping journal/launchd output across nodes.
        log.info("messenger v" + Version.VERSION + " starting (java "
            + System.getProperty("java.version") + ")");

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

        // v1.2 dedup cache (issue #16). Shared between the poller (which reads
        // it on each inbound to short-circuit duplicates) and the processors
        // (which write the actual reply payload to it after sendReply). Created
        // here so both halves of the pipeline reference the same instance.
        DedupCache dedupCache = new DedupCache();

        // v1.2 progress beats (issue #17). Wipe any stale per-thread log files
        // left from a prior daemon run; in-flight conversations did not survive
        // the restart, so resurrecting their tail bytes would lie to the peer.
        // The runtime directory itself is created by systemd via
        // RuntimeDirectory=messenger/progress (see messenger.service); on dev
        // boxes / macOS the directory may not exist, in which case skip silently.
        cleanupProgressDir(ClaudeCliProcessor.PROGRESS_DIR);

        // RelaySender is shared between the ping auto-pong (issue #14) and the
        // progress-beat scheduler (issue #17): both POST to localhost/relay so
        // outbound progress and pong messages traverse the same code path as
        // every other relay caller.
        RelaySender relaySender = new HttpRelaySender(client, config.listenPort);
        ProgressBeatScheduler progressBeatScheduler = new ProgressBeatScheduler(
            relaySender, config.nodeName);

        // Processor selection — config.processor overrides auto-detection:
        //   "gemma"     → GemmaProcessor only (dedicated Ollama agent)
        //   "claude-cli"→ ClaudeCliProcessor only
        //   null/absent → auto priority: ClaudeCliProcessor → GemmaProcessor → logging
        MessageProcessor processor;
        if ("gemma".equals(config.processor)) {
            log.info("Processor forced to Gemma via config");
            processor = GemmaProcessor.create(client, config, dedupCache);
            if (processor == null) {
                log.severe("processor=gemma requested but Ollama is unreachable — exiting.");
                System.exit(1);
            }
        } else if ("claude-cli".equals(config.processor)) {
            log.info("Processor forced to Claude CLI via config");
            processor = ClaudeCliProcessor.create(client, config, brain, dedupCache, progressBeatScheduler);
            if (processor == null) {
                log.severe("processor=claude-cli requested but claude binary not found — exiting.");
                System.exit(1);
            }
        } else {
            // Auto priority:
            //   1. ClaudeCliProcessor — claude -p CLI, uses subscription (free), full tool use
            //   2. GemmaProcessor     — local Ollama, works without internet
            //   3. logging()          — safe no-op fallback
            processor = ClaudeCliProcessor.create(client, config, brain, dedupCache, progressBeatScheduler);
            if (processor == null) processor = GemmaProcessor.create(client, config, dedupCache);
            if (processor == null) {
                processor = MessageProcessor.logging();
                // Loud warning — a node in logging-noop mode will poll messages,
                // call the no-op, mark them archived, and report
                // messages_processed=0. Looks healthy from outside but does nothing.
                // macbook-air hit exactly this for 8 days; the banner is meant to
                // surface it immediately at startup.
                log.severe("⚠ NO PROCESSOR AVAILABLE — falling back to logging() no-op."
                    + " Claude CLI not found AND Gemma/Ollama unreachable."
                    + " This node will appear healthy but will not actually process messages."
                    + " Fix: ensure 'claude' is on PATH, or set processor=gemma in config"
                    + " and ensure OLLAMA_URL is reachable.");
            }
        }
        log.info("Active processor: " + processor.name());

        MessagePoller poller = new MessagePoller(config, brain, processor, relaySender, dedupCache);
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
        server.createContext("/health",    new HealthHandler(config, poller, java.time.Instant.now(), processor));
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
            + "' version=" + Version.VERSION
            + " port=" + config.listenPort
            + " openBrain=" + config.openBrainUrl
            + " config_source=" + config.source);
    }

    /**
     * Wipe stale per-thread progress logs from a previous daemon run (issue #17).
     * In-flight conversations did not survive the restart, so resurrecting their
     * tail bytes would lie to the peer. The directory itself is created by
     * systemd via {@code RuntimeDirectory=messenger/progress}; on dev boxes /
     * macOS the directory may be absent — silently skip in that case.
     * Package-private for testing.
     */
    static void cleanupProgressDir(Path progressDir) {
        if (progressDir == null || !Files.isDirectory(progressDir)) return;
        try (Stream<Path> entries = Files.list(progressDir)) {
            entries.forEach(p -> {
                try { Files.deleteIfExists(p); }
                catch (IOException e) {
                    log.warning("Could not delete stale progress log " + p + ": " + e.getMessage());
                }
            });
            log.info("Cleaned stale progress logs from " + progressDir);
        } catch (IOException e) {
            log.warning("Could not list progress dir " + progressDir + ": " + e.getMessage());
        }
    }
}
