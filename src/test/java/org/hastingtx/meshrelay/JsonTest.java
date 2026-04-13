package org.hastingtx.meshrelay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Json recursive-descent parser and typed accessors.
 */
class JsonTest {

    // ── Primitive parsing ────────────────────────────────────────────────

    @Nested
    class Primitives {

        @Test
        void parseString() {
            Json j = Json.parse("\"hello\"");
            assertTrue(j.isString());
            assertEquals("hello", j.asString());
        }

        @Test
        void parseInt() {
            Json j = Json.parse("42");
            assertTrue(j.isNumber());
            assertEquals("42", j.asString());
        }

        @Test
        void parseNegativeInt() {
            Json j = Json.parse("-7");
            assertTrue(j.isNumber());
            assertEquals("-7", j.asString());
        }

        @Test
        void parseFloat() {
            Json j = Json.parse("3.14");
            assertTrue(j.isNumber());
        }

        @Test
        void parseScientific() {
            Json j = Json.parse("1.5e10");
            assertTrue(j.isNumber());
        }

        @Test
        void parseTrue() {
            Json j = Json.parse("true");
            assertEquals("true", j.asString());
        }

        @Test
        void parseFalse() {
            Json j = Json.parse("false");
            assertEquals("false", j.asString());
        }

        @Test
        void parseNull() {
            Json j = Json.parse("null");
            assertTrue(j.isNull());
            assertNull(j.asString());
        }
    }

    // ── String escapes ───────────────────────────────────────────────────

    @Nested
    class StringEscapes {

        @Test
        void escapedQuotes() {
            Json j = Json.parse("{\"msg\":\"he said \\\"hi\\\"\"}");
            assertEquals("he said \"hi\"", j.getString("msg"));
        }

        @Test
        void escapedBackslash() {
            Json j = Json.parse("{\"path\":\"C:\\\\Users\\\\rick\"}");
            assertEquals("C:\\Users\\rick", j.getString("path"));
        }

        @Test
        void escapedNewlineAndTab() {
            Json j = Json.parse("{\"text\":\"line1\\nline2\\ttab\"}");
            assertEquals("line1\nline2\ttab", j.getString("text"));
        }

        @Test
        void unicodeEscape() {
            Json j = Json.parse("{\"char\":\"\\u0041\"}");
            assertEquals("A", j.getString("char"));
        }

        @Test
        void escapedSlash() {
            Json j = Json.parse("{\"url\":\"http:\\/\\/example.com\"}");
            assertEquals("http://example.com", j.getString("url"));
        }
    }

    // ── Objects ──────────────────────────────────────────────────────────

    @Nested
    class Objects {

        @Test
        void simpleObject() {
            Json j = Json.parse("{\"name\":\"test\",\"count\":42}");
            assertTrue(j.isObject());
            assertEquals("test", j.getString("name"));
            assertEquals(42, j.getInt("count"));
        }

        @Test
        void emptyObject() {
            Json j = Json.parse("{}");
            assertTrue(j.isObject());
            assertEquals(0, j.size());
        }

        @Test
        void nestedObject() {
            Json j = Json.parse("{\"outer\":{\"inner\":\"value\"}}");
            assertEquals("value", j.get("outer").getString("inner"));
        }

        @Test
        void deeplyNested() {
            Json j = Json.parse("{\"a\":{\"b\":{\"c\":{\"d\":\"deep\"}}}}");
            assertEquals("deep", j.get("a").get("b").get("c").getString("d"));
        }

        @Test
        void objectWithAllTypes() {
            String json = """
                {"str":"hello","num":42,"flt":3.14,"bool":true,"nil":null,"arr":[1,2],"obj":{"k":"v"}}
                """;
            Json j = Json.parse(json);
            assertEquals("hello", j.getString("str"));
            assertEquals(42, j.getInt("num"));
            assertTrue(j.has("flt"));
            assertTrue(j.has("bool"));
            assertTrue(j.has("nil"));
            assertEquals(2, j.get("arr").size());
            assertEquals("v", j.get("obj").getString("k"));
        }

        @Test
        void missingKeyReturnsNull() {
            Json j = Json.parse("{\"a\":1}");
            assertNull(j.getString("missing"));
        }

        @Test
        void hasReturnsFalseForMissing() {
            Json j = Json.parse("{\"a\":1}");
            assertTrue(j.has("a"));
            assertFalse(j.has("b"));
        }

        @Test
        void getIntWithDefault() {
            Json j = Json.parse("{\"a\":1}");
            assertEquals(1, j.getInt("a", 99));
            assertEquals(99, j.getInt("missing", 99));
        }

        @Test
        void getLong() {
            Json j = Json.parse("{\"big\":9999999999}");
            assertEquals(9999999999L, j.getLong("big"));
        }

        @Test
        void getLongWithDefault() {
            Json j = Json.parse("{\"a\":1}");
            assertEquals(1L, j.getLong("a", 0L));
            assertEquals(0L, j.getLong("missing", 0L));
        }

        @Test
        void getStringConvertsNumbers() {
            Json j = Json.parse("{\"num\":42}");
            assertEquals("42", j.getString("num"));
        }

        @Test
        void getStringWithDefault() {
            Json j = Json.parse("{\"a\":\"val\"}");
            assertEquals("val", j.getString("a", "default"));
            assertEquals("default", j.getString("missing", "default"));
        }
    }

    // ── Arrays ──────────────────────────────────────────────────────────

    @Nested
    class Arrays {

        @Test
        void simpleArray() {
            Json j = Json.parse("[1,2,3]");
            assertTrue(j.isArray());
            assertEquals(3, j.size());
        }

        @Test
        void emptyArray() {
            Json j = Json.parse("[]");
            assertTrue(j.isArray());
            assertEquals(0, j.size());
            assertTrue(j.asList().isEmpty());
        }

        @Test
        void arrayOfObjects() {
            Json j = Json.parse("[{\"id\":1},{\"id\":2},{\"id\":3}]");
            List<Json> items = j.asList();
            assertEquals(3, items.size());
            assertEquals(1, items.get(0).getInt("id"));
            assertEquals(2, items.get(1).getInt("id"));
            assertEquals(3, items.get(2).getInt("id"));
        }

        @Test
        void arrayOfStrings() {
            Json j = Json.parse("[\"from:linuxserver\",\"to:macmini\",\"mesh-message\"]");
            List<Json> tags = j.asList();
            assertEquals(3, tags.size());
            assertEquals("from:linuxserver", tags.get(0).asString());
            assertEquals("to:macmini", tags.get(1).asString());
        }

        @Test
        void nestedArrays() {
            Json j = Json.parse("[[1,2],[3,4]]");
            assertEquals(2, j.size());
            assertEquals(2, j.asList().get(0).size());
        }

        @Test
        void mixedArray() {
            Json j = Json.parse("[\"str\",42,true,null,{\"k\":\"v\"},[1]]");
            List<Json> items = j.asList();
            assertEquals(6, items.size());
            assertTrue(items.get(0).isString());
            assertTrue(items.get(1).isNumber());
            assertTrue(items.get(3).isNull());
            assertTrue(items.get(4).isObject());
            assertTrue(items.get(5).isArray());
        }
    }

    // ── Null-safe navigation ─────────────────────────────────────────────

    @Nested
    class NullSafety {

        @Test
        void getMissingKeyThenGetString() {
            Json j = Json.parse("{\"a\":1}");
            assertNull(j.get("missing").getString("nested"));
        }

        @Test
        void getMissingKeyThenAsList() {
            Json j = Json.parse("{\"a\":1}");
            assertTrue(j.get("missing").asList().isEmpty());
        }

        @Test
        void getMissingKeyThenHas() {
            Json j = Json.parse("{\"a\":1}");
            assertFalse(j.get("missing").has("anything"));
        }

        @Test
        void getMissingKeyThenSize() {
            Json j = Json.parse("{\"a\":1}");
            assertEquals(0, j.get("missing").size());
        }

        @Test
        void getOnNonObject() {
            Json j = Json.parse("[1,2,3]");
            assertNull(j.get("key").asString());
        }

        @Test
        void getIntDefaultOnNonObject() {
            Json j = Json.parse("\"hello\"");
            assertEquals(99, j.getInt("key", 99));
        }
    }

    // ── Json.escape() ────────────────────────────────────────────────────

    @Nested
    class Escape {

        @Test
        void escapesQuotes() {
            assertEquals("he said \\\"hi\\\"", Json.escape("he said \"hi\""));
        }

        @Test
        void escapesBackslash() {
            assertEquals("C:\\\\Users", Json.escape("C:\\Users"));
        }

        @Test
        void escapesNewlineAndTab() {
            assertEquals("a\\nb\\tc", Json.escape("a\nb\tc"));
        }

        @Test
        void escapesCarriageReturn() {
            assertEquals("a\\rb", Json.escape("a\rb"));
        }

        @Test
        void nullReturnsEmpty() {
            assertEquals("", Json.escape(null));
        }

        @Test
        void roundTrip() {
            String original = "line1\nline2\t\"quoted\" back\\slash";
            String escaped = Json.escape(original);
            Json parsed = Json.parse("{\"v\":\"" + escaped + "\"}");
            assertEquals(original, parsed.getString("v"));
        }
    }

    // ── Error handling ───────────────────────────────────────────────────

    @Nested
    class Errors {

        @Test
        void nullInput() {
            assertThrows(IllegalArgumentException.class, () -> Json.parse(null));
        }

        @Test
        void emptyInput() {
            assertThrows(IllegalArgumentException.class, () -> Json.parse(""));
        }

        @Test
        void blankInput() {
            assertThrows(IllegalArgumentException.class, () -> Json.parse("   "));
        }

        @Test
        void truncatedObject() {
            assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":"));
        }

        @Test
        void truncatedArray() {
            assertThrows(IllegalArgumentException.class, () -> Json.parse("[1,2,"));
        }

        @Test
        void truncatedString() {
            assertThrows(IllegalArgumentException.class, () -> Json.parse("\"unterminated"));
        }

        @Test
        void invalidToken() {
            assertThrows(IllegalArgumentException.class, () -> Json.parse("undefined"));
        }
    }

    // ── OpenBrain response format ────────────────────────────────────────

    @Nested
    class OpenBrainFormat {

        @Test
        void parseBrowseThoughtsResponse() {
            String response = """
                [
                  {
                    "id": 579,
                    "content": "System health report",
                    "tags": ["from:linuxserver", "to:macmini", "mesh-message"],
                    "status": "active",
                    "project": "mesh-messages",
                    "node": "linuxserver",
                    "thread_id": 100
                  },
                  {
                    "id": 580,
                    "content": "Reply from macmini",
                    "tags": ["from:macmini", "to:linuxserver", "mesh-message"],
                    "status": "active",
                    "project": "mesh-messages",
                    "node": "macmini"
                  }
                ]
                """;

            Json thoughts = Json.parse(response);
            assertTrue(thoughts.isArray());
            assertEquals(2, thoughts.size());

            Json first = thoughts.asList().get(0);
            assertEquals(579, first.getInt("id"));
            assertEquals("System health report", first.getString("content"));
            assertEquals("active", first.getString("status"));
            assertEquals(100, first.getLong("thread_id"));

            // Tags are a string array
            List<Json> tags = first.get("tags").asList();
            assertEquals(3, tags.size());
            assertEquals("from:linuxserver", tags.get(0).asString());
            assertEquals("to:macmini", tags.get(1).asString());

            // Second thought has no thread_id
            Json second = thoughts.asList().get(1);
            assertEquals(580, second.getInt("id"));
            assertFalse(second.has("thread_id"));
        }

        @Test
        void parseCaptureThoughtResponse() {
            String response = "{\"id\":462,\"status\":\"active\"}";
            Json j = Json.parse(response);
            assertEquals(462, j.getInt("id"));
        }

        @Test
        void contentWithNestedJsonInString() {
            // Content field contains JSON as a string value — should not confuse parser
            String response = """
                [{"id":1,"content":"Config: {\\"listen_port\\": 13007, \\"peers\\": []}","tags":[]}]
                """;
            Json thoughts = Json.parse(response);
            Json first = thoughts.asList().get(0);
            assertEquals(1, first.getInt("id"));
            assertTrue(first.getString("content").contains("listen_port"));
        }

        @Test
        void contentWithBracesAndQuotes() {
            // The case that broke the old regex parser
            String response = """
                [{"id":99,"content":"function() { return \\"hello\\"; }","tags":["to:all"]}]
                """;
            Json thoughts = Json.parse(response);
            Json first = thoughts.asList().get(0);
            assertEquals(99, first.getInt("id"));
            assertEquals("function() { return \"hello\"; }", first.getString("content"));
        }
    }

    // ── Processor response formats ───────────────────────────────────────

    @Nested
    class ProcessorFormats {

        @Test
        void ollamaChatResponse() {
            String response = """
                {"message":{"role":"assistant","content":"Hello from Gemma"},"done":true}
                """;
            Json j = Json.parse(response);
            assertEquals("Hello from Gemma", j.get("message").getString("content"));
            assertEquals("assistant", j.get("message").getString("role"));
        }

        @Test
        void claudeApiResponse() {
            String response = """
                {"content":[{"type":"text","text":"Hello from Claude"}],"model":"claude-sonnet-4-6"}
                """;
            Json j = Json.parse(response);
            List<Json> blocks = j.get("content").asList();
            assertEquals(1, blocks.size());
            assertEquals("text", blocks.get(0).getString("type"));
            assertEquals("Hello from Claude", blocks.get(0).getString("text"));
        }

        @Test
        void claudeCliJsonOutput() {
            String response = """
                {"result":"Task completed successfully","session_id":"abc-123-def"}
                """;
            Json j = Json.parse(response);
            assertEquals("Task completed successfully", j.getString("result"));
            assertEquals("abc-123-def", j.getString("session_id"));
        }
    }

    // ── Whitespace tolerance ─────────────────────────────────────────────

    @Nested
    class Whitespace {

        @Test
        void leadingAndTrailingWhitespace() {
            Json j = Json.parse("  { \"a\" : 1 }  ");
            assertEquals(1, j.getInt("a"));
        }

        @Test
        void newlinesInObject() {
            Json j = Json.parse("{\n  \"a\": 1,\n  \"b\": 2\n}");
            assertEquals(1, j.getInt("a"));
            assertEquals(2, j.getInt("b"));
        }
    }
}
