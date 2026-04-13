package org.hastingtx.meshrelay;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.logging.Logger;
/**
 * Loads peer configuration — OpenBrain is the primary source of truth.
 *
 * Load order:
 *   1. Read node_name + OpenBrain credentials from bootstrap file / env vars.
 *   2. Fetch full config from OpenBrain (browse_thoughts project=messenger, node=<name>).
 *   3. Write result to local cache (~/.messenger-config.cache) for next startup.
 *   4. If OpenBrain unreachable → load from local cache → log warning.
 *   5. If no cache → fall back to the bootstrap file itself (minimal config).
 *
 * Bootstrap file (config.json) — only these fields are required:
 * {
 *   "node_name":     "linuxserver",
 *   "openbrain_url": "http://192.168.0.226:3000"
 * }
 * openbrain_key may be in config.json or OPENBRAIN_KEY env var.
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

    private PeerConfig(String nodeName, int listenPort, Map<String, String> peers,
                       String openBrainUrl, String openBrainKey, String source,
                       String processor) {
        this.nodeName     = nodeName;
        this.listenPort   = listenPort;
        this.peers        = Collections.unmodifiableMap(peers);
        this.openBrainUrl = openBrainUrl;
        this.openBrainKey = openBrainKey;
        this.source       = source;
        this.processor    = processor;
    }

    /**
     * Load config using the full strategy: OpenBrain → cache → local file.
     * The configFile is the bootstrap file (needs only node_name + openbrain credentials).
     */
    public static PeerConfig load(Path configFile) throws IOException {
        String bootstrap = Files.readString(configFile);
        Json boot = Json.parse(bootstrap);

        String nodeName = boot.getString("node_name");
        if (nodeName == null) throw new IllegalArgumentException("Missing config key: node_name");
        String obUrl = boot.getString("openbrain_url", "http://192.168.0.226:3000");
        String obKey = System.getenv("OPENBRAIN_KEY");
        if (obKey == null) obKey = boot.getString("openbrain_key", "axlv8KWl_wHBmjylHkltJF0R4gkRjDPW0ibx-yp7bUQ");

        Path cacheFile = Path.of(System.getProperty("user.home"), ".messenger-config.cache");

        // 1. Try OpenBrain
        try {
            HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
            String configJson = fetchFromOpenBrain(http, obUrl, obKey, nodeName);
            PeerConfig cfg = parseConfig(configJson, nodeName, obUrl, obKey, "openbrain");
            writeCache(cacheFile, configJson);
            log.info("Config loaded from OpenBrain (node=" + nodeName + ")");
            return cfg;
        } catch (Exception e) {
            log.warning("OpenBrain config unavailable: " + e.getMessage());
        }

        // 2. Try local cache (last known good from OpenBrain)
        if (Files.exists(cacheFile)) {
            try {
                String cached = Files.readString(cacheFile);
                PeerConfig cfg = parseConfig(cached, nodeName, obUrl, obKey, "cache");
                log.warning("Config loaded from local cache — OpenBrain was unreachable");
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
        if (resp.statusCode() != 200)
            throw new IOException("OpenBrain returned HTTP " + resp.statusCode());

        Json thoughts = Json.parse(resp.body());
        if (!thoughts.isArray() || thoughts.size() == 0)
            throw new IOException("No config thought found in OpenBrain for node=" + nodeName);

        return thoughts.asList().get(0).getString("content");
    }

    /**
     * Parse a config JSON string (the content field from an OpenBrain thought,
     * or the bootstrap file). Expects: listen_port and peers array.
     */
    private static PeerConfig parseConfig(String json, String nodeName,
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

        return new PeerConfig(nodeName, listenPort, peers, obUrl, obKey, source, processor);
    }

    private static void writeCache(Path cacheFile, String content) {
        try {
            Files.writeString(cacheFile, content);
        } catch (IOException e) {
            log.warning("Could not write config cache: " + e.getMessage());
        }
    }

    /** Return the URL for a named peer, or null if unknown. */
    public String urlFor(String peerName) {
        return peers.get(peerName);
    }

    @Override
    public String toString() {
        return "PeerConfig{node=" + nodeName + ", port=" + listenPort
            + ", peers=" + peers.keySet() + ", source=" + source
            + (processor != null ? ", processor=" + processor : "") + "}";
    }
}
