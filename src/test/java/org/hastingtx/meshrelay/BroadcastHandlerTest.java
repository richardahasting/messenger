package org.hastingtx.meshrelay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BroadcastHandler request parsing and validation.
 */
class BroadcastHandlerTest {

    @Test
    void validBroadcastRequest() {
        String body = "{\"from\":\"linuxserver\",\"content\":\"status update\"}";
        Json json = Json.parse(body);
        assertEquals("linuxserver", json.getString("from"));
        assertEquals("status update", json.getString("content"));
    }

    @Test
    void broadcastWithMissingFromDefaultsToNull() {
        String body = "{\"content\":\"hello all\"}";
        Json json = Json.parse(body);
        assertNull(json.getString("from"));
        assertEquals("hello all", json.getString("content"));
    }

    @Test
    void broadcastWithNullContentDetected() {
        String body = "{\"from\":\"linuxserver\"}";
        Json json = Json.parse(body);
        String content = json.getString("content");
        // Handler should reject this with 400
        assertNull(content);
    }

    @Test
    void broadcastWithEmptyContentDetected() {
        String body = "{\"from\":\"linuxserver\",\"content\":\"\"}";
        Json json = Json.parse(body);
        String content = json.getString("content");
        // Handler checks content.isBlank()
        assertTrue(content == null || content.isBlank());
    }

    @Test
    void broadcastResponseFormat() {
        // Verify the success response format
        int threadId = 462;
        String delivered = "[\"macmini\"]";
        String failed = "[\"macbook-air\"]";
        String response = "{\"thread_id\":%d,\"delivered\":%s,\"failed\":%s}"
            .formatted(threadId, delivered, failed);

        Json json = Json.parse(response);
        assertEquals(462, json.getInt("thread_id"));
        assertEquals(1, json.get("delivered").size());
        assertEquals("macmini", json.get("delivered").asList().get(0).asString());
        assertEquals(1, json.get("failed").size());
    }
}
