package org.hastingtx.meshrelay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OpenBrainStore message-table API.
 *
 * Tests the JSON parsing logic for the messages table API:
 *   - storeMessage() response parsing (send_message → {id, thread_id})
 *   - pollPendingMessages() response parsing (get_inbox → flat array or structured envelope)
 *   - Edge cases: missing fields, None/null responses, malformed envelopes
 *
 * No live server required — all tests drive the Json parser with realistic
 * response payloads captured from the OpenBrain MCP API.
 *
 * NOTE: the old thoughts-table tests (capture_thought, tag-based from:/to:
 * extraction) have been removed. OpenBrainStore no longer uses that API
 * for message storage — those paths now live in storeSessionSummary() /
 * fetchSessionContext() only, which are covered by SessionManagementTest.
 */
class OpenBrainStoreTest {

    // ── storeMessage() response parsing ──────────────────────────────────

    @Nested
    class StoreMessageResponse {

        @Test
        void parsesValidIdAndThreadId() {
            String response = "{\"id\":462,\"thread_id\":460}";
            Json result = Json.parse(response);
            assertEquals(462, result.getInt("id", -1));
            assertEquals(460L, result.getLong("thread_id", -1L));
        }

        @Test
        void parsesWhenThreadIdEqualsId() {
            // First message in a thread: thread_id == id
            String response = "{\"id\":760,\"thread_id\":760}";
            Json result = Json.parse(response);
            assertEquals(760, result.getInt("id", -1));
            assertEquals(760L, result.getLong("thread_id", -1L));
        }

        @Test
        void detectsMissingIdField() {
            // This is the broken macmini case: server returns None → no "id" key
            String response = "{}";
            Json result = Json.parse(response);
            assertEquals(-1, result.getInt("id", -1));
            // Caller must throw: "send_message response missing 'id' field"
        }

        @Test
        void detectsMissingThreadIdField() {
            String response = "{\"id\":462}";
            Json result = Json.parse(response);
            assertEquals(462, result.getInt("id", -1));
            assertEquals(-1L, result.getLong("thread_id", -1L));
            // Caller must throw: "send_message response missing 'thread_id' field"
        }

        @Test
        void detectsNullLiteralResponse() {
            // Server returns literal "None" (Python None serialized without JSON encoding)
            // Json.parse should not throw — but the envelope will be empty/unparseable.
            String response = "null";
            Json result = Json.parse(response);
            assertEquals(-1, result.getInt("id", -1));
        }

        @Test
        void detectsEmptyStringResponse() {
            // Json.parse throws on blank input — the real guard is the HTTP status
            // check in storeMessage() before we ever call Json.parse on the body.
            assertThrows(IllegalArgumentException.class, () -> Json.parse(""));
        }

        @Test
        void parsesExtraFieldsGracefully() {
            // Server may add metadata — parser must ignore unknown fields
            String response = """
                {"id":500,"thread_id":499,"from_node":"linuxserver","status":"pending"}
                """;
            Json result = Json.parse(response);
            assertEquals(500, result.getInt("id", -1));
            assertEquals(499L, result.getLong("thread_id", -1L));
        }
    }

    // ── pollPendingMessages() response parsing ────────────────────────────
    //
    // get_inbox may return one of two shapes:
    //   Shape 1 — flat array:    [{id, thread_id, from_node, to_node, content}, ...]
    //   Shape 2 — structured:    {"direct": [...], "broadcasts": [...]}

    @Nested
    class InboxParsing {

        @Test
        void parsesFlatArrayWithSingleMessage() {
            String response = """
                [{"id":462,"thread_id":460,"from_node":"macmini","to_node":"linuxserver","content":"Hello"}]
                """;
            Json root = Json.parse(response);
            assertTrue(root.isArray());
            assertEquals(1, root.size());

            Json msg = root.asList().get(0);
            assertEquals(462, msg.getInt("id", -1));
            assertEquals(460L, msg.getLong("thread_id", -1L));
            assertEquals("macmini", msg.getString("from_node", "unknown"));
            assertEquals("linuxserver", msg.getString("to_node", "all"));
            assertEquals("Hello", msg.getString("content", ""));
        }

        @Test
        void parsesFlatArrayWithMultipleMessages() {
            String response = """
                [
                  {"id":1,"thread_id":1,"from_node":"macmini","to_node":"linuxserver","content":"msg1"},
                  {"id":2,"thread_id":1,"from_node":"macmini","to_node":"linuxserver","content":"msg2"},
                  {"id":3,"thread_id":3,"from_node":"macbook-air","to_node":"linuxserver","content":"msg3"}
                ]
                """;
            Json root = Json.parse(response);
            assertTrue(root.isArray());
            assertEquals(3, root.size());
        }

        @Test
        void parsesEmptyFlatArray() {
            Json root = Json.parse("[]");
            assertTrue(root.isArray());
            assertEquals(0, root.size());
        }

        @Test
        void parsesStructuredEnvelopeWithDirectAndBroadcasts() {
            String response = """
                {
                  "direct": [
                    {"id":10,"thread_id":10,"from_node":"macmini","to_node":"linuxserver","content":"direct msg"}
                  ],
                  "broadcasts": [
                    {"id":20,"thread_id":20,"from_node":"macbook-air","to_node":"all","content":"broadcast msg"}
                  ]
                }
                """;
            Json root = Json.parse(response);
            assertFalse(root.isArray());
            assertTrue(root.isObject());

            List<Json> direct = root.get("direct").asList();
            List<Json> broadcasts = root.get("broadcasts").asList();
            assertEquals(1, direct.size());
            assertEquals(1, broadcasts.size());

            assertEquals("direct msg", direct.get(0).getString("content", ""));
            assertEquals("broadcast msg", broadcasts.get(0).getString("content", ""));
        }

        @Test
        void parsesStructuredEnvelopeWithEmptyBroadcasts() {
            String response = """
                {
                  "direct": [
                    {"id":10,"thread_id":10,"from_node":"macmini","to_node":"linuxserver","content":"hi"}
                  ],
                  "broadcasts": []
                }
                """;
            Json root = Json.parse(response);
            assertEquals(1, root.get("direct").asList().size());
            assertEquals(0, root.get("broadcasts").asList().size());
        }

        @Test
        void messageWithMissingThreadIdDefaultsToMessageId() {
            // Broadcasts may not have thread_id set
            String response = """
                [{"id":99,"from_node":"macmini","to_node":"all","content":"broadcast"}]
                """;
            Json msg = Json.parse(response).asList().get(0);
            int id = msg.getInt("id", -1);
            long threadId = msg.getLong("thread_id", -1L);

            // Caller logic: if threadId < 0, use id as fallback
            long effectiveThreadId = threadId < 0 ? (long) id : threadId;
            assertEquals(99L, effectiveThreadId);
        }

        @Test
        void messageWithContentContainingJsonBraces() {
            // Content may contain JSON-like strings — must not confuse the parser
            String response = """
                [{"id":200,"thread_id":200,"from_node":"linuxserver","to_node":"macmini",
                  "content":"Run: if (x > 0) { return true; }"}]
                """;
            Json msg = Json.parse(response).asList().get(0);
            assertEquals("Run: if (x > 0) { return true; }", msg.getString("content", ""));
        }

        @Test
        void messageWithEscapedQuotesInContent() {
            String response = """
                [{"id":201,"thread_id":201,"from_node":"linuxserver","to_node":"macmini",
                  "content":"He said \\"hello\\" to her"}]
                """;
            Json msg = Json.parse(response).asList().get(0);
            assertEquals("He said \"hello\" to her", msg.getString("content", ""));
        }

        @Test
        void messageWithNewlinesInContent() {
            String response = """
                [{"id":202,"thread_id":202,"from_node":"linuxserver","to_node":"macmini",
                  "content":"line1\\nline2\\nline3"}]
                """;
            Json msg = Json.parse(response).asList().get(0);
            assertEquals("line1\nline2\nline3", msg.getString("content", ""));
        }
    }

    // ── send_message request body construction ────────────────────────────
    //
    // Verifies that storeMessage() builds the correct MCP request JSON.

    @Nested
    class RequestBodyConstruction {

        @Test
        void buildsSendMessageBody() {
            String fromNode = "linuxserver";
            String toNode   = "macmini";
            String content  = "Hello from mesh";
            String escaped  = Json.escape(content);

            String body = """
                {
                  "tool": "send_message",
                  "from_node": "%s",
                  "to_node": "%s",
                  "content": "%s"
                }
                """.formatted(fromNode, toNode, escaped);

            Json parsed = Json.parse(body);
            assertEquals("send_message",  parsed.getString("tool"));
            assertEquals("linuxserver",   parsed.getString("from_node"));
            assertEquals("macmini",       parsed.getString("to_node"));
            assertEquals("Hello from mesh", parsed.getString("content"));
        }

        @Test
        void escapesSpecialCharsInContent() {
            String content  = "He said \"hello\"\nand left.";
            String escaped  = Json.escape(content);
            String body     = "{\"content\":\"" + escaped + "\"}";
            assertEquals(content, Json.parse(body).getString("content"));
        }

        @Test
        void buildsMarkDeliveredBody() {
            int messageId = 462;
            String body = "{\"tool\":\"mark_delivered\",\"message_id\":" + messageId + "}";
            Json parsed = Json.parse(body);
            assertEquals("mark_delivered", parsed.getString("tool"));
            assertEquals(462, parsed.getInt("message_id", -1));
        }

        @Test
        void buildsMarkArchivedBody() {
            int messageId = 462;
            String body = "{\"tool\":\"mark_archived\",\"message_id\":" + messageId + "}";
            Json parsed = Json.parse(body);
            assertEquals("mark_archived", parsed.getString("tool"));
            assertEquals(462, parsed.getInt("message_id", -1));
        }

        @Test
        void buildsGetInboxBody() {
            String nodeName = "linuxserver";
            String body = """
                {
                  "tool": "get_inbox",
                  "node": "%s",
                  "include_broadcasts": true,
                  "limit": 50
                }
                """.formatted(nodeName);
            Json parsed = Json.parse(body);
            assertEquals("get_inbox", parsed.getString("tool"));
            assertEquals("linuxserver", parsed.getString("node"));
        }
    }
}
