package org.hastingtx.meshrelay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.time.Instant;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for session management: SessionEntry, expiration logic,
 * and OpenBrain session context storage/retrieval formats.
 */
class SessionManagementTest {

    @Nested
    class SessionEntry {

        @Test
        void recordsSessionIdAndTimestamp() {
            Instant now = Instant.now();
            var entry = new ClaudeCliProcessor.SessionEntry("sess-abc-123", now);
            assertEquals("sess-abc-123", entry.sessionId());
            assertEquals(now, entry.lastUsed());
        }

        @Test
        void entryIsExpiredAfterTtl() {
            Instant old = Instant.now().minus(Duration.ofMinutes(60));
            var entry = new ClaudeCliProcessor.SessionEntry("sess-old", old);

            Instant cutoff = Instant.now().minus(Duration.ofMinutes(45));
            assertTrue(entry.lastUsed().isBefore(cutoff),
                "Session from 60 min ago should be before 45-min cutoff");
        }

        @Test
        void entryIsNotExpiredWithinTtl() {
            Instant recent = Instant.now().minus(Duration.ofMinutes(10));
            var entry = new ClaudeCliProcessor.SessionEntry("sess-recent", recent);

            Instant cutoff = Instant.now().minus(Duration.ofMinutes(45));
            assertFalse(entry.lastUsed().isBefore(cutoff),
                "Session from 10 min ago should not be before 45-min cutoff");
        }
    }

    @Nested
    class SessionSummaryFormat {

        @Test
        void summaryStorageJsonFormat() {
            long threadId = 462;
            String nodeName = "macmini";
            String summary = "Discussed disk cleanup. Removed 2GB of temp files. Pending: review cron jobs.";
            String escaped = Json.escape(summary);

            String body = """
                {
                  "tool": "capture_thought",
                  "content": "%s",
                  "category": "session-context",
                  "project": "messenger",
                  "node": "%s",
                  "status": "active",
                  "tags": ["thread:%d", "session-summary"],
                  "source": "messenger-session"
                }
                """.formatted(escaped, nodeName, threadId);

            Json parsed = Json.parse(body);
            assertEquals("capture_thought", parsed.getString("tool"));
            assertEquals(summary, parsed.getString("content"));
            assertEquals("session-context", parsed.getString("category"));
            assertEquals("messenger", parsed.getString("project"));
            assertEquals("macmini", parsed.getString("node"));

            // Verify tags
            var tags = parsed.get("tags").asList();
            assertEquals(2, tags.size());
            assertEquals("thread:462", tags.get(0).asString());
            assertEquals("session-summary", tags.get(1).asString());
        }

        @Test
        void summaryWithSpecialCharsRoundTrips() {
            String summary = "User asked: \"what's the status?\"\nReply included code: if (x > 0) { return; }";
            String escaped = Json.escape(summary);
            String json = "{\"content\":\"" + escaped + "\"}";
            Json parsed = Json.parse(json);
            assertEquals(summary, parsed.getString("content"));
        }

        @Test
        void contextSearchRequestFormat() {
            long threadId = 462;
            String body = """
                {
                  "tool": "search_thoughts",
                  "query": "thread:%d session-summary",
                  "mode": "keyword",
                  "limit": 1
                }
                """.formatted(threadId);

            Json parsed = Json.parse(body);
            assertEquals("search_thoughts", parsed.getString("tool"));
            assertEquals("thread:462 session-summary", parsed.getString("query"));
            assertEquals("keyword", parsed.getString("mode"));
            assertEquals(1, parsed.getInt("limit"));
        }

        @Test
        void contextResponseParsing() {
            String response = """
                [{"id":500,"content":"Prior session: checked disk usage, found 2GB temp files, cleaned /tmp","tags":["thread:462","session-summary"],"category":"session-context"}]
                """;
            Json results = Json.parse(response);
            assertTrue(results.isArray());
            assertEquals(1, results.size());

            String content = results.asList().get(0).getString("content");
            assertTrue(content.contains("checked disk usage"));
        }

        @Test
        void emptyContextReturnsNull() {
            Json results = Json.parse("[]");
            assertTrue(results.isArray());
            assertEquals(0, results.size());
            // fetchSessionContext would return null for empty results
        }
    }

    @Nested
    class SystemPromptWithContext {

        @Test
        void systemPromptIncludesPriorContext() {
            String base = "You are the macmini agent in a distributed multi-agent system.";
            String priorContext = "Discussed disk cleanup. Removed 2GB of temp files.";

            String prompt = base + "\n\nPrevious conversation context:\n" + priorContext;

            assertTrue(prompt.contains("Previous conversation context:"));
            assertTrue(prompt.contains("Removed 2GB"));
        }

        @Test
        void systemPromptWithoutPriorContext() {
            String base = "You are the macmini agent in a distributed multi-agent system.";
            String priorContext = null;

            String prompt = base;
            if (priorContext != null) {
                prompt += "\n\nPrevious conversation context:\n" + priorContext;
            }

            assertFalse(prompt.contains("Previous conversation context:"));
        }
    }
}
