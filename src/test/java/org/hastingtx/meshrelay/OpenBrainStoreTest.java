package org.hastingtx.meshrelay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for OpenBrainStore message parsing and JSON building.
 *
 * These test the internal parsing logic by exercising Json-based parsing
 * on realistic OpenBrain response payloads, without requiring a live server.
 */
class OpenBrainStoreTest {

    // ── Message parsing (simulating parseMessages behavior) ──────────────

    @Nested
    class MessageParsing {

        @Test
        void parseSingleMessage() {
            String response = """
                [{"id":462,"content":"Hello from linuxserver","tags":["to:macmini","from:linuxserver","mesh-message"],"status":"active","project":"mesh-messages"}]
                """;
            Json thoughts = Json.parse(response);
            List<Json> list = thoughts.asList();
            assertEquals(1, list.size());

            Json msg = list.get(0);
            assertEquals(462, msg.getInt("id"));
            assertEquals("Hello from linuxserver", msg.getString("content"));

            // Extract from/to from tags
            String from = "unknown", to = "all";
            for (Json tag : msg.get("tags").asList()) {
                String t = tag.asString();
                if (t.startsWith("from:")) from = t.substring(5);
                else if (t.startsWith("to:")) to = t.substring(3);
            }
            assertEquals("linuxserver", from);
            assertEquals("macmini", to);
        }

        @Test
        void parseMultipleMessages() {
            String response = """
                [
                  {"id":1,"content":"msg1","tags":["to:macmini","from:linuxserver"],"status":"active"},
                  {"id":2,"content":"msg2","tags":["to:macmini","from:macbook-air"],"status":"active"},
                  {"id":3,"content":"msg3","tags":["to:all","from:linuxserver"],"status":"active"}
                ]
                """;
            Json thoughts = Json.parse(response);
            assertEquals(3, thoughts.size());
        }

        @Test
        void parseEmptyResponse() {
            Json thoughts = Json.parse("[]");
            assertTrue(thoughts.isArray());
            assertEquals(0, thoughts.size());
        }

        @Test
        void messageWithThreadId() {
            String response = """
                [{"id":10,"content":"reply","tags":["to:linuxserver","from:macmini"],"thread_id":5}]
                """;
            Json msg = Json.parse(response).asList().get(0);
            assertEquals(5, msg.getLong("thread_id"));
        }

        @Test
        void messageWithoutThreadIdDefaultsToId() {
            String response = """
                [{"id":10,"content":"original","tags":["to:macmini","from:linuxserver"]}]
                """;
            Json msg = Json.parse(response).asList().get(0);
            assertFalse(msg.has("thread_id"));
            // Caller should default to id when thread_id is absent
            long threadId = msg.has("thread_id") ? msg.getLong("thread_id") : (long) msg.getInt("id");
            assertEquals(10L, threadId);
        }

        @Test
        void messageWithNestedBracesInContent() {
            // This was the case that broke the old regex parser
            String response = """
                [{"id":99,"content":"Run: if (x > 0) { return true; }","tags":["to:macmini","from:linuxserver"]}]
                """;
            Json msg = Json.parse(response).asList().get(0);
            assertEquals("Run: if (x > 0) { return true; }", msg.getString("content"));
        }

        @Test
        void messageWithEscapedQuotesInContent() {
            String response = """
                [{"id":100,"content":"He said \\"hello\\" to her","tags":["to:macmini","from:linuxserver"]}]
                """;
            Json msg = Json.parse(response).asList().get(0);
            assertEquals("He said \"hello\" to her", msg.getString("content"));
        }

        @Test
        void messageWithNewlinesInContent() {
            String response = """
                [{"id":101,"content":"line1\\nline2\\nline3","tags":["to:macmini","from:linuxserver"]}]
                """;
            Json msg = Json.parse(response).asList().get(0);
            assertEquals("line1\nline2\nline3", msg.getString("content"));
        }

        @Test
        void missingTagsFieldHandledGracefully() {
            String response = """
                [{"id":200,"content":"no tags"}]
                """;
            Json msg = Json.parse(response).asList().get(0);
            // get("tags") on missing key returns Json(null), asList() returns empty
            assertTrue(msg.get("tags").asList().isEmpty());
        }

        @Test
        void nullTagsHandledGracefully() {
            String response = """
                [{"id":201,"content":"null tags","tags":null}]
                """;
            Json msg = Json.parse(response).asList().get(0);
            assertTrue(msg.get("tags").asList().isEmpty());
        }
    }

    // ── JSON building (capture_thought format) ───────────────────────────

    @Nested
    class JsonBuilding {

        @Test
        void escapeContentForCapture() {
            String content = "Check if (x > 0) { return \"yes\"; }";
            String escaped = Json.escape(content);
            String json = "{\"content\":\"" + escaped + "\"}";

            // Should parse back correctly
            Json parsed = Json.parse(json);
            assertEquals(content, parsed.getString("content"));
        }

        @Test
        void escapeMultilineContent() {
            String content = "Line 1\nLine 2\tTabbed\rCarriage";
            String escaped = Json.escape(content);
            String json = "{\"content\":\"" + escaped + "\"}";

            Json parsed = Json.parse(json);
            assertEquals(content, parsed.getString("content"));
        }

        @Test
        void captureThoughtJsonFormat() {
            String fromNode = "linuxserver";
            String toNode = "macmini";
            String content = "Hello \"world\"";
            String escaped = Json.escape(content);

            String body = """
                {
                  "tool": "capture_thought",
                  "content": "%s",
                  "category": "relationship",
                  "project": "mesh-messages",
                  "node": "%s",
                  "status": "active",
                  "tags": ["to:%s", "from:%s", "mesh-message"],
                  "source": "mesh-relay"
                }
                """.formatted(escaped, fromNode, toNode, fromNode);

            Json parsed = Json.parse(body);
            assertEquals("capture_thought", parsed.getString("tool"));
            assertEquals("Hello \"world\"", parsed.getString("content"));
            assertEquals("linuxserver", parsed.getString("node"));

            List<Json> tags = parsed.get("tags").asList();
            assertEquals(3, tags.size());
            assertEquals("to:macmini", tags.get(0).asString());
            assertEquals("from:linuxserver", tags.get(1).asString());
        }
    }

    // ── ID extraction (capture_thought response) ─────────────────────────

    @Nested
    class IdExtraction {

        @Test
        void extractIdFromCaptureResponse() {
            String response = "{\"id\":462,\"content\":\"stored\",\"status\":\"active\"}";
            assertEquals(462, Json.parse(response).getInt("id", -1));
        }

        @Test
        void missingIdReturnsDefault() {
            String response = "{\"status\":\"active\"}";
            assertEquals(-1, Json.parse(response).getInt("id", -1));
        }
    }
}
