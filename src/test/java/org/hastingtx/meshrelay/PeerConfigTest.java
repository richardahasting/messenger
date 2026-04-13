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
}
