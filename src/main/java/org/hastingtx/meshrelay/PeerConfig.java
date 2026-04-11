package org.hastingtx.meshrelay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;

/**
 * Loads peer configuration from the existing agent-mesh config.json.
 * Reuses the same file so there is one source of truth for the mesh topology.
 *
 * Config format (same as current Python daemon):
 * {
 *   "node_name": "linuxserver",
 *   "listen_port": 13003,
 *   "peers": [
 *     { "name": "macmini",      "url": "http://192.168.0.226:13003" },
 *     { "name": "macbook-air",  "url": "http://192.168.0.62:13003"  }
 *   ]
 * }
 */
public class PeerConfig {

    public final String nodeName;
    public final int    listenPort;
    public final Map<String, String> peers;       // name → base URL
    public final String openBrainUrl;             // e.g. http://192.168.0.226:3000
    public final String openBrainKey;             // x-brain-key header value

    private PeerConfig(String nodeName, int listenPort, Map<String, String> peers,
                       String openBrainUrl, String openBrainKey) {
        this.nodeName     = nodeName;
        this.listenPort   = listenPort;
        this.peers        = Collections.unmodifiableMap(peers);
        this.openBrainUrl = openBrainUrl;
        this.openBrainKey = openBrainKey;
    }

    public static PeerConfig load(Path configFile) throws IOException {
        String json = Files.readString(configFile);

        String nodeName   = extractString(json, "node_name");
        int    listenPort = extractInt(json, "listen_port");

        Map<String, String> peers = new LinkedHashMap<>();
        // Extract peers array — minimal regex parser (no external JSON lib)
        Matcher peerBlock = Pattern.compile(
            "\\{[^{}]*\"name\"\\s*:\\s*\"([^\"]+)\"[^{}]*\"url\"\\s*:\\s*\"([^\"]+)\"[^{}]*\\}"
        ).matcher(json);
        while (peerBlock.find()) {
            peers.put(peerBlock.group(1), peerBlock.group(2));
        }

        // OpenBrain URL — from config, env var, or default to macmini
        String obUrl = extractStringOpt(json, "openbrain_url");
        if (obUrl == null) obUrl = "http://192.168.0.226:3000";

        // OpenBrain key — env var takes precedence over config
        String obKey = System.getenv("OPENBRAIN_KEY");
        if (obKey == null) obKey = extractStringOpt(json, "openbrain_key");
        if (obKey == null) obKey = "axlv8KWl_wHBmjylHkltJF0R4gkRjDPW0ibx-yp7bUQ";

        return new PeerConfig(nodeName, listenPort, peers, obUrl, obKey);
    }

    private static String extractString(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        if (!m.find()) throw new IllegalArgumentException("Missing config key: " + key);
        return m.group(1);
    }

    private static String extractStringOpt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static int extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(json);
        if (!m.find()) throw new IllegalArgumentException("Missing config key: " + key);
        return Integer.parseInt(m.group(1));
    }

    /** Return the URL for a named peer, or null if unknown. */
    public String urlFor(String peerName) {
        return peers.get(peerName);
    }

    @Override
    public String toString() {
        return "PeerConfig{node=" + nodeName + ", port=" + listenPort + ", peers=" + peers.keySet() + "}";
    }
}
