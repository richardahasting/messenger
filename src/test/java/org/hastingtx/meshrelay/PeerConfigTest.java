package org.hastingtx.meshrelay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PeerConfig parsing and validation.
 */
class PeerConfigTest {

    @Test
    void parseMinimalConfig() {
        String json = "{\"node_name\":\"testnode\",\"openbrain_url\":\"http://localhost:3000\"}";
        Json boot = Json.parse(json);
        assertEquals("testnode", boot.getString("node_name"));
        assertEquals("http://localhost:3000", boot.getString("openbrain_url"));
    }

    @Test
    void parseConfigWithPeers() {
        String json = """
            {
              "node_name": "linuxserver",
              "openbrain_url": "http://192.168.0.226:3000",
              "listen_port": 13007,
              "peers": [
                {"name": "macmini", "url": "http://192.168.0.226:13007"},
                {"name": "macbook-air", "url": "http://192.168.0.62:13007"}
              ]
            }
            """;
        Json cfg = Json.parse(json);
        assertEquals("linuxserver", cfg.getString("node_name"));
        assertEquals(13007, cfg.getInt("listen_port"));

        var peers = cfg.get("peers").asList();
        assertEquals(2, peers.size());
        assertEquals("macmini", peers.get(0).getString("name"));
        assertEquals("http://192.168.0.226:13007", peers.get(0).getString("url"));
        assertEquals("macbook-air", peers.get(1).getString("name"));
    }

    @Test
    void parseConfigWithProcessor() {
        String json = "{\"node_name\":\"test\",\"processor\":\"gemma\"}";
        Json cfg = Json.parse(json);
        assertEquals("gemma", cfg.getString("processor"));
    }

    @Test
    void missingNodeNameDetected() {
        String json = "{\"openbrain_url\":\"http://localhost:3000\"}";
        Json boot = Json.parse(json);
        assertNull(boot.getString("node_name"));
    }

    @Test
    void defaultListenPort() {
        String json = "{\"node_name\":\"test\"}";
        Json cfg = Json.parse(json);
        assertEquals(13007, cfg.getInt("listen_port", 13007));
    }

    @Test
    void defaultOpenBrainUrl() {
        String json = "{\"node_name\":\"test\"}";
        Json boot = Json.parse(json);
        assertEquals("http://192.168.0.226:3000",
            boot.getString("openbrain_url", "http://192.168.0.226:3000"));
    }

    @Test
    void openbrain_keyFromConfig() {
        String json = "{\"node_name\":\"test\",\"openbrain_key\":\"my-secret-key\"}";
        Json boot = Json.parse(json);
        assertEquals("my-secret-key", boot.getString("openbrain_key"));
    }

    @Test
    void missingOpenbrain_keyReturnsNull() {
        String json = "{\"node_name\":\"test\"}";
        Json boot = Json.parse(json);
        assertNull(boot.getString("openbrain_key"));
    }

    @Test
    void noPeersReturnsEmptyArray() {
        String json = "{\"node_name\":\"test\",\"listen_port\":13007}";
        Json cfg = Json.parse(json);
        assertFalse(cfg.has("peers"));
        assertTrue(cfg.get("peers").asList().isEmpty());
    }

    @Test
    void emptyPeersArray() {
        String json = "{\"node_name\":\"test\",\"peers\":[]}";
        Json cfg = Json.parse(json);
        assertTrue(cfg.get("peers").asList().isEmpty());
    }

    // ── Claude CLI processor settings ────────────────────────────────────

    @Test
    void claudeModelDefault() {
        String json = "{\"node_name\":\"test\"}";
        Json cfg = Json.parse(json);
        assertEquals("claude-sonnet-4-6", cfg.getString("claude_model", "claude-sonnet-4-6"));
    }

    @Test
    void claudeModelOverride() {
        String json = "{\"node_name\":\"test\",\"claude_model\":\"claude-opus-4-6\"}";
        Json cfg = Json.parse(json);
        assertEquals("claude-opus-4-6", cfg.getString("claude_model", "claude-sonnet-4-6"));
    }

    @Test
    void claudeTimeoutDefault() {
        String json = "{\"node_name\":\"test\"}";
        Json cfg = Json.parse(json);
        assertEquals(12, cfg.getInt("claude_timeout_minutes", 12));
    }

    @Test
    void claudeTimeoutOverride() {
        String json = "{\"node_name\":\"test\",\"claude_timeout_minutes\":20}";
        Json cfg = Json.parse(json);
        assertEquals(20, cfg.getInt("claude_timeout_minutes", 12));
    }

    @Test
    void sessionTtlDefault() {
        String json = "{\"node_name\":\"test\"}";
        Json cfg = Json.parse(json);
        assertEquals(240, cfg.getInt("session_ttl_minutes", 240));
    }

    @Test
    void sessionTtlOverride() {
        String json = "{\"node_name\":\"test\",\"session_ttl_minutes\":60}";
        Json cfg = Json.parse(json);
        assertEquals(60, cfg.getInt("session_ttl_minutes", 240));
    }

    @Test
    void reaperIntervalDefault() {
        String json = "{\"node_name\":\"test\"}";
        Json cfg = Json.parse(json);
        assertEquals(5, cfg.getInt("reaper_interval_minutes", 5));
    }

    @Test
    void reaperIntervalOverride() {
        String json = "{\"node_name\":\"test\",\"reaper_interval_minutes\":10}";
        Json cfg = Json.parse(json);
        assertEquals(10, cfg.getInt("reaper_interval_minutes", 5));
    }

    @Test
    void fullConfigWithAllSettings() {
        String json = """
            {
              "node_name": "macmini",
              "listen_port": 13007,
              "processor": "claude-cli",
              "claude_model": "claude-opus-4-6",
              "claude_timeout_minutes": 15,
              "session_ttl_minutes": 120,
              "reaper_interval_minutes": 10,
              "peers": [{"name": "linuxserver", "url": "http://192.168.0.225:13007"}]
            }
            """;
        Json cfg = Json.parse(json);
        assertEquals("macmini", cfg.getString("node_name"));
        assertEquals("claude-cli", cfg.getString("processor"));
        assertEquals("claude-opus-4-6", cfg.getString("claude_model", "claude-sonnet-4-6"));
        assertEquals(15, cfg.getInt("claude_timeout_minutes", 12));
        assertEquals(120, cfg.getInt("session_ttl_minutes", 240));
        assertEquals(10, cfg.getInt("reaper_interval_minutes", 5));
    }
}
