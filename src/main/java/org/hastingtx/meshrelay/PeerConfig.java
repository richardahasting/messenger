package org.hastingtx.meshrelay;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
/**
 * Loads peer configuration — OpenBrain is the primary source of truth.
 *
 * Load order:
 *   1. Read node_name + OpenBrain credentials from bootstrap file / env vars.
 *   2. Fetch full config from OpenBrain (browse_thoughts project=messenger, node=<name>).
 *      Network failures are retried with backoff for up to openbrain_startup_wait_seconds
 *      (default 60), so a boot-time race with OpenBrain doesn't strand us on fallback
 *      config. A definite "no" (HTTP 4xx, no config thought) is not retried.
 *   3. Write result to this instance's cache (~/.messenger-config-<node_name>.cache).
 *   4. If OpenBrain unavailable → load this instance's cache → log warning. The cache
 *      records which node wrote it; a cache for any other node is ignored (#35).
 *   5. If no usable cache → fall back to the bootstrap file itself (minimal config).
 *
 * Bootstrap file (config.json) — only these fields are required:
 * {
 *   "node_name":     "linuxserver",
 *   "openbrain_url": "http://192.168.0.226:3000"
 * }
 * openbrain_key may be in config.json or OPENBRAIN_KEY env var.
 * A sibling instance on the same host should also set listen_port (and processor) here,
 * so the step-5 fallback can't land on the default port 13007.
 *
 * Full config stored in OpenBrain as a thought:
 *   singleton_key = "messenger-config-<node_name>"
 *   project       = "messenger"
 *   task_type     = "config"
 *   node          = "<node_name>"
 *   expires_at    = null  (never expires)
 *   content       = { "listen_port": 13007, "peers": [...] }
 */
public class PeerConfig {

    private static final Logger log = Logger.getLogger(PeerConfig.class.getName());

    public final String nodeName;
    public final int    listenPort;
    public final Map<String, String> peers;       // name → base URL
    public final String openBrainUrl;
    public final String openBrainKey;
    public final String source;                   // "openbrain", "cache", or "local"
    /** Optional processor override: "gemma" | "claude-cli" | null (auto). */
    public final String processor;

    // ── Claude CLI processor settings (configurable, with defaults) ───────
    public final String claudeModel;
    public final int    claudeTimeoutMinutes;
    public final int    sessionTtlMinutes;
    public final int    reaperIntervalMinutes;
    /**
     * Max messages processed concurrently across all threads. Bounds the number
     * of simultaneous {@code claude -p} subprocesses so a burst can't exhaust the
     * machine. Different conversation threads process concurrently (messenger#27);
     * this caps that concurrency. Default 4.
     */
    public final int    maxConcurrentProcessing;
    /** Optional personality/voice for this node's agent. Null for default. */
    public final String personality;

    private PeerConfig(String nodeName, int listenPort, Map<String, String> peers,
                       String openBrainUrl, String openBrainKey, String source,
                       String processor, String claudeModel, int claudeTimeoutMinutes,
                       int sessionTtlMinutes, int reaperIntervalMinutes,
                       int maxConcurrentProcessing,
                       String personality) {
        this.nodeName              = nodeName;
        this.listenPort            = listenPort;
        this.peers                 = Collections.unmodifiableMap(peers);
        this.openBrainUrl          = openBrainUrl;
        this.openBrainKey          = openBrainKey;
        this.source                = source;
        this.processor             = processor;
        this.claudeModel           = claudeModel;
        this.claudeTimeoutMinutes  = claudeTimeoutMinutes;
        this.sessionTtlMinutes     = sessionTtlMinutes;
        this.reaperIntervalMinutes = reaperIntervalMinutes;
        this.maxConcurrentProcessing = maxConcurrentProcessing;
        this.personality           = personality;
    }

    /**
     * Load config using the full strategy: OpenBrain → cache → local file.
     * The configFile is the bootstrap file (needs only node_name + openbrain credentials).
     */
    public static PeerConfig load(Path configFile) throws IOException {
        HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        return load(Files.readString(configFile), System.getenv("OPENBRAIN_KEY"),
            Path.of(System.getProperty("user.home")),
            (url, key, node) -> fetchFromOpenBrain(http, url, key, node),
            Thread::sleep);
    }

    /** Longest single pause between OpenBrain attempts during startup. */
    private static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(15);

    /** Fetches this node's config JSON (the thought's content) from OpenBrain. */
    @FunctionalInterface
    interface ConfigFetcher {
        String fetch(String obUrl, String obKey, String nodeName) throws Exception;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    /** OpenBrain answered, but no: bad key, no config thought. Retrying won't change that. */
    static class ConfigRejectedException extends IOException {
        ConfigRejectedException(String message) { super(message); }
    }

    /**
     * One cache file per instance (#35). Before this, every instance on a host shared
     * ~/.messenger-config.cache, so the last one to start decided what the others would
     * fall back to. Keyed by node name, which is what the cached config describes.
     */
    static Path cacheFileFor(Path homeDir, String nodeName) {
        String safe = nodeName.replaceAll("[^A-Za-z0-9._-]", "_");
        return homeDir.resolve(".messenger-config-" + safe + ".cache");
    }

    static PeerConfig load(String bootstrap, String envKey, Path homeDir,
                           ConfigFetcher fetcher, Sleeper sleeper) throws IOException {
        Json boot = Json.parse(bootstrap);

        String nodeName = boot.getString("node_name");
        if (nodeName == null) throw new IllegalArgumentException("Missing config key: node_name");
        String obUrl = boot.getString("openbrain_url", "http://192.168.0.226:3000");
        String obKey = envKey;
        if (obKey == null) obKey = boot.getString("openbrain_key");
        if (obKey == null) {
            throw new IllegalArgumentException(
                "OpenBrain key not found. Set OPENBRAIN_KEY env var or openbrain_key in config.json");
        }

        Path cacheFile = cacheFileFor(homeDir, nodeName);
        Duration startupWait = Duration.ofSeconds(
            Math.max(0, boot.getInt("openbrain_startup_wait_seconds", 60)));

        // 1. Try OpenBrain, riding out a boot-time race for up to startupWait
        try {
            String configJson = fetchWithRetry(fetcher, obUrl, obKey, nodeName, startupWait, sleeper);
            PeerConfig cfg = parseConfig(configJson, nodeName, obUrl, obKey, "openbrain");
            writeCache(cacheFile, nodeName, configJson);
            log.info("Config loaded from OpenBrain (node=" + nodeName + ")");
            return cfg;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warning("Interrupted while waiting for OpenBrain config");
        } catch (Exception e) {
            log.warning("OpenBrain config unavailable: " + describe(e));
        }

        // 2. Try this instance's cache (last known good from OpenBrain)
        String cached = readCache(cacheFile, nodeName);
        if (cached != null) {
            try {
                PeerConfig cfg = parseConfig(cached, nodeName, obUrl, obKey, "cache");
                log.warning("Config loaded from " + cacheFile + " — OpenBrain was unavailable");
                return cfg;
            } catch (Exception e) {
                log.warning("Cache unreadable: " + e.getMessage());
            }
        }

        // 3. Fall back to the bootstrap file itself (minimal local config)
        log.warning("Falling back to local bootstrap config — no OpenBrain, no cache");
        return parseConfig(bootstrap, nodeName, obUrl, obKey, "local");
    }

    /**
     * Fetch with exponential backoff (1s, 2s, 4s … capped at {@link #MAX_RETRY_DELAY})
     * until {@code budget} of waiting is used up. Only network-level failures are
     * retried; a {@link ConfigRejectedException} or a malformed response is final.
     */
    private static String fetchWithRetry(ConfigFetcher fetcher, String obUrl, String obKey,
                                         String nodeName, Duration budget, Sleeper sleeper)
            throws Exception {
        Duration waited = Duration.ZERO;
        Duration delay  = Duration.ofSeconds(1);
        for (int attempt = 1; ; attempt++) {
            try {
                return fetcher.fetch(obUrl, obKey, nodeName);
            } catch (ConfigRejectedException e) {
                throw e;
            } catch (IOException e) {
                Duration remaining = budget.minus(waited);
                if (!remaining.isPositive()) throw e;
                Duration pause = delay.compareTo(remaining) < 0 ? delay : remaining;
                log.warning("OpenBrain config attempt " + attempt + " failed (" + describe(e)
                    + ") — retrying in " + pause.toSeconds() + "s");
                sleeper.sleep(pause);
                waited = waited.plus(pause);
                delay  = delay.multipliedBy(2).compareTo(MAX_RETRY_DELAY) < 0
                    ? delay.multipliedBy(2) : MAX_RETRY_DELAY;
            }
        }
    }

    /**
     * Call OpenBrain browse_thoughts to fetch this node's config thought.
     * Returns the content field of the matching thought.
     */
    private static String fetchFromOpenBrain(HttpClient http,
                                              String obUrl,
                                              String obKey,
                                              String nodeName) throws Exception {
        String body = "{\"tool\":\"browse_thoughts\","
            + "\"project\":\"messenger\","
            + "\"node\":\"" + Json.escape(nodeName) + "\","
            + "\"task_type\":\"config\","
            + "\"limit\":1}";

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(obUrl + "/mcp"))
            .header("Content-Type", "application/json")
            .header("x-brain-key", obKey)
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        int status = resp.statusCode();
        if (status != 200) {
            String msg = "OpenBrain returned HTTP " + status;
            // 4xx means OpenBrain is up and said no (e.g. a stale key). Waiting won't help.
            // 408/429 and 5xx can clear on their own, so they stay retryable.
            boolean rejected = status >= 400 && status < 500 && status != 408 && status != 429;
            throw rejected ? new ConfigRejectedException(msg) : new IOException(msg);
        }

        Json thoughts = Json.parse(resp.body());
        if (!thoughts.isArray() || thoughts.size() == 0)
            throw new ConfigRejectedException("No config thought found in OpenBrain for node=" + nodeName);

        return thoughts.asList().get(0).getString("content");
    }

    /**
     * Parse a config JSON string (the content field from an OpenBrain thought,
     * or the bootstrap file). Expects: listen_port and peers array.
     */
    static PeerConfig parseConfig(String json, String nodeName,
                                   String obUrl, String obKey,
                                   String source) {
        Json cfg = Json.parse(json);
        int listenPort = cfg.getInt("listen_port", 13007);

        Map<String, String> peers = new LinkedHashMap<>();
        if (cfg.has("peers")) {
            for (Json peer : cfg.get("peers").asList()) {
                peers.put(peer.getString("name"), peer.getString("url"));
            }
        }

        String processor = cfg.getString("processor");

        String claudeModel          = cfg.getString("claude_model", "claude-sonnet-4-6");
        int    claudeTimeoutMinutes = cfg.getInt("claude_timeout_minutes", 12);
        int    sessionTtlMinutes    = cfg.getInt("session_ttl_minutes", 240);
        int    reaperIntervalMinutes = cfg.getInt("reaper_interval_minutes", 5);
        int    maxConcurrentProcessing = cfg.getInt("max_concurrent_processing", 4);
        String personality          = cfg.getString("personality");

        return new PeerConfig(nodeName, listenPort, peers, obUrl, obKey, source,
            processor, claudeModel, claudeTimeoutMinutes, sessionTtlMinutes,
            reaperIntervalMinutes, maxConcurrentProcessing, personality);
    }

    /**
     * Cache format: {"node_name": "...", "cached_at": "...", "config_json": "<thought content>"}.
     * The node name lets {@link #readCache} refuse a file written by a different instance.
     * Written to a temp file and moved into place so a crash can't leave half a file.
     */
    private static void writeCache(Path cacheFile, String nodeName, String content) {
        String envelope = "{\"node_name\":\"" + Json.escape(nodeName) + "\","
            + "\"cached_at\":\"" + Instant.now() + "\","
            + "\"config_json\":\"" + Json.escape(content) + "\"}";
        try {
            Path tmp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
            Files.writeString(tmp, envelope);
            Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING,
                       StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warning("Could not write config cache: " + e.getMessage());
        }
    }

    /** Returns the cached config JSON if the cache exists and belongs to {@code nodeName}. */
    private static String readCache(Path cacheFile, String nodeName) {
        if (!Files.exists(cacheFile)) return null;
        try {
            Json envelope = Json.parse(Files.readString(cacheFile));
            String owner   = envelope.getString("node_name");
            String content = envelope.getString("config_json");
            if (owner == null || content == null) {
                log.warning("Ignoring " + cacheFile + " — it doesn't record which node wrote it");
                return null;
            }
            if (!owner.equals(nodeName)) {
                log.severe("Ignoring " + cacheFile + " — it holds node=" + owner
                    + "'s config, not " + nodeName + "'s");
                return null;
            }
            return content;
        } catch (Exception e) {
            log.warning("Cache unreadable: " + e.getMessage());
            return null;
        }
    }

    /** ConnectException and friends often have a null message; keep the type so the log says something. */
    private static String describe(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName()
                                      : e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    /** Return the URL for a named peer, or null if unknown. */
    public String urlFor(String peerName) {
        return peers.get(peerName);
    }

    @Override
    public String toString() {
        return "PeerConfig{node=" + nodeName + ", port=" + listenPort
            + ", peers=" + peers.keySet() + ", source=" + source
            + (processor != null ? ", processor=" + processor : "")
            + ", claudeModel=" + claudeModel
            + ", timeout=" + claudeTimeoutMinutes + "m"
            + ", sessionTTL=" + sessionTtlMinutes + "m"
            + ", reaperInterval=" + reaperIntervalMinutes + "m"
            + ", maxConcurrent=" + maxConcurrentProcessing + "}";
    }
}
