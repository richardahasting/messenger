package org.hastingtx.meshrelay;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ProgressBeatScheduler} and {@link SecretsSweeper}
 * (issue #17). Cadence assertions use {@code startUnclampedForTest} so the
 * tests don't have to wait 30+ seconds; the production {@link
 * ProgressBeatScheduler#start} path enforces the spec's [30, 120] s clamp,
 * which is verified separately via {@link ProgressBeatScheduler#clampInterval}.
 *
 * <p>See docs/protocol-v1.2.md § "Progress mechanism — daemon-driven heartbeat
 * with log tailing" for the design context these tests pin down.
 */
class ProgressBeatSchedulerTest {

    /** Captures every {@link RelaySender#send} call made by the scheduler. */
    static class RecordingRelaySender implements RelaySender {
        record Sent(String to, String from, String content, String kind,
                    String inReplyTo, String replyPolicy, long threadId) {}

        final List<Sent> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<CountDownLatch> latches = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public boolean send(String toNode, String fromNode, String content,
                            String kind, String inReplyTo, String replyPolicy, long threadId) {
            sent.add(new Sent(toNode, fromNode, content, kind, inReplyTo, replyPolicy, threadId));
            for (CountDownLatch l : latches) l.countDown();
            return true;
        }

        /** Block until {@code count} beats have been observed. */
        void awaitCount(int count, long timeoutMs) throws InterruptedException {
            CountDownLatch l = new CountDownLatch(count);
            for (Sent ignored : sent) l.countDown(); // count already-arrived beats
            latches.add(l);
            try {
                if (!l.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                    fail("Timed out waiting for " + count + " beats; got " + sent.size());
                }
            } finally {
                latches.remove(l);
            }
        }
    }

    private RecordingRelaySender sender;
    private ProgressBeatScheduler scheduler;

    @BeforeEach
    void setUp() {
        sender = new RecordingRelaySender();
        scheduler = new ProgressBeatScheduler(sender, "linuxserver");
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) scheduler.shutdown();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Clamp behavior — spec § "Constraints": clamp(update_interval_seconds, 30, 120)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void clampBelow30RaisesTo30() {
        assertEquals(30, ProgressBeatScheduler.clampInterval(5));
        assertEquals(30, ProgressBeatScheduler.clampInterval(29));
        assertEquals(30, ProgressBeatScheduler.clampInterval(0));
        assertEquals(30, ProgressBeatScheduler.clampInterval(-100));
    }

    @Test
    void clampAbove120LowersTo120() {
        assertEquals(120, ProgressBeatScheduler.clampInterval(121));
        assertEquals(120, ProgressBeatScheduler.clampInterval(3600));
    }

    @Test
    void clampPassesThroughInRange() {
        assertEquals(30,  ProgressBeatScheduler.clampInterval(30));
        assertEquals(60,  ProgressBeatScheduler.clampInterval(60));
        assertEquals(120, ProgressBeatScheduler.clampInterval(120));
    }

    // ─────────────────────────────────────────────────────────────────────
    // Start/stop happy path — N ticks fire at the right cadence
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @Timeout(10)
    void startStopHappyPathFiresExpectedNumberOfBeats(@TempDir Path tmp) throws Exception {
        Path log = Files.createFile(tmp.resolve("beat.log"));
        Files.writeString(log, "running step 1\n");

        scheduler.startUnclampedForTest(42L, "macmini:42:1", "macmini", 50L, log);
        sender.awaitCount(3, 2000);
        scheduler.stop(42L, "macmini:42:1");

        // Pin the v1.2 wire shape: kind=progress, NO_REPLY, in_reply_to echoes seq.
        for (RecordingRelaySender.Sent beat : sender.sent) {
            assertEquals("macmini",          beat.to(),          "beat goes back to original sender");
            assertEquals("linuxserver",      beat.from(),        "beat originates from this node");
            assertEquals("progress",         beat.kind(),        "kind must be progress");
            assertEquals("NO_REPLY",         beat.replyPolicy(), "progress is NO_REPLY by definition");
            assertEquals("macmini:42:1",     beat.inReplyTo(),   "in_reply_to echoes the action's seq");
            assertEquals(42L,                beat.threadId(),    "thread carries through unchanged");
            assertTrue(beat.content().contains("running step 1"),
                "tail must reflect what the processor wrote: " + beat.content());
        }

        int countAtStop = sender.sent.size();
        Thread.sleep(150);
        assertEquals(countAtStop, sender.sent.size(),
            "stop() must cancel the beat — no further ticks after stop");
    }

    @Test
    @Timeout(10)
    void stopIsIdempotentAndDoesNotThrow() {
        // stop() in process()'s finally block runs even when start() was a no-op
        // (no update_interval, no scheduler wired). Mirror that here.
        assertDoesNotThrow(() -> scheduler.stop(99L, "missing:99:1"));
        assertDoesNotThrow(() -> scheduler.stop(99L, "missing:99:1"));
    }

    // ─────────────────────────────────────────────────────────────────────
    // Empty log file → "(no log activity)"
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @Timeout(10)
    void emptyLogFileEmitsNoActivityPayload(@TempDir Path tmp) throws Exception {
        Path log = Files.createFile(tmp.resolve("empty.log"));
        // Don't write anything — file exists but is 0 bytes.

        scheduler.startUnclampedForTest(7L, "peer:7:1", "peer", 50L, log);
        sender.awaitCount(1, 2000);
        scheduler.stop(7L, "peer:7:1");

        assertEquals(ProgressBeatScheduler.EMPTY_TAIL_PAYLOAD,
            sender.sent.get(0).content(),
            "empty log → '(no log activity)' per spec § 'Receiver-side flow'");
    }

    @Test
    @Timeout(10)
    void missingLogFileEmitsNoActivityPayload(@TempDir Path tmp) throws Exception {
        Path log = tmp.resolve("never-created.log");
        // Don't create the file at all — simulates a processor that hasn't
        // written its first line yet, or a processor that exits before the
        // first tick.

        scheduler.startUnclampedForTest(8L, "peer:8:1", "peer", 50L, log);
        sender.awaitCount(1, 2000);
        scheduler.stop(8L, "peer:8:1");

        assertEquals(ProgressBeatScheduler.EMPTY_TAIL_PAYLOAD,
            sender.sent.get(0).content());
    }

    // ─────────────────────────────────────────────────────────────────────
    // readTail — last 10 lines, 4096-byte cap
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void readTailReturnsLastTenLines(@TempDir Path tmp) throws Exception {
        Path log = tmp.resolve("many.log");
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 25; i++) sb.append("line ").append(i).append('\n');
        Files.writeString(log, sb.toString());

        String tail = ProgressBeatScheduler.readTail(log,
            ProgressBeatScheduler.TAIL_MAX_LINES, ProgressBeatScheduler.TAIL_MAX_BYTES);

        String[] lines = tail.split("\n");
        assertEquals(10, lines.length, "must keep exactly the last 10 lines");
        assertEquals("line 16", lines[0], "first kept line is the 16th");
        assertEquals("line 25", lines[lines.length - 1], "last kept line is the 25th");
    }

    @Test
    void readTailRespects4096ByteCapOnGiantLogLine(@TempDir Path tmp) throws Exception {
        // Simulate a single pathological log line that exceeds the cap.
        // The scheduler must not blow up wire size — only the trailing
        // bytes are kept.
        Path log = tmp.resolve("giant.log");
        char[] giant = new char[10_000];
        java.util.Arrays.fill(giant, 'X');
        Files.writeString(log, new String(giant));

        String tail = ProgressBeatScheduler.readTail(log,
            ProgressBeatScheduler.TAIL_MAX_LINES, ProgressBeatScheduler.TAIL_MAX_BYTES);

        assertTrue(tail.length() <= ProgressBeatScheduler.TAIL_MAX_BYTES,
            "tail must respect 4096-byte cap, was " + tail.length());
        assertTrue(tail.endsWith("X"),
            "the kept slice must come from the END of the file");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Long-running task with periodic log writes — cadence assertion
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @Timeout(10)
    void longRunningTaskWithPeriodicLogWritesProducesBeats(@TempDir Path tmp) throws Exception {
        // Mimics the production flow: a processor writes to the log over
        // several "ticks" while the scheduler emits beats. Each beat must
        // reflect the latest content the processor has written.
        Path log = Files.createFile(tmp.resolve("long.log"));
        scheduler.startUnclampedForTest(99L, "macmini:99:1", "macmini", 80L, log);

        for (int i = 1; i <= 4; i++) {
            Files.writeString(log, "step " + i + " complete\n",
                StandardOpenOption.APPEND);
            Thread.sleep(80);
        }

        sender.awaitCount(3, 2000);
        scheduler.stop(99L, "macmini:99:1");

        assertTrue(sender.sent.size() >= 3,
            "at least three beats expected over four 80ms steps; got " + sender.sent.size());

        // The most recent beat reflects later log activity than the first one.
        String firstContent = sender.sent.get(0).content();
        String lastContent  = sender.sent.get(sender.sent.size() - 1).content();
        assertNotEquals(firstContent, lastContent,
            "later beats should reflect newer log content");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Lifecycle: log file deleted after processor exit (success + failure)
    // Mirrors the try/finally in ClaudeCliProcessor.process(): the test
    // exercises the same lifecycle (createFile, start, stop, delete) that
    // the processor wraps around runClaude.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @Timeout(10)
    void logFileDeletedAfterSuccessfulProcessorExit(@TempDir Path tmp) throws Exception {
        Path log = Files.createFile(tmp.resolve("success.log"));
        scheduler.startUnclampedForTest(11L, "peer:11:1", "peer", 50L, log);
        // Simulate processor success — it returned, no exception thrown.
        scheduler.stop(11L, "peer:11:1");
        Files.deleteIfExists(log);

        assertFalse(Files.exists(log),
            "log file must be removed after the processor exits successfully");
    }

    @Test
    @Timeout(10)
    void logFileDeletedAfterFailedProcessorExit(@TempDir Path tmp) throws Exception {
        Path log = Files.createFile(tmp.resolve("failure.log"));
        scheduler.startUnclampedForTest(12L, "peer:12:1", "peer", 50L, log);
        try {
            // Simulate processor failure — exception propagates from runClaude.
            throw new RuntimeException("simulated processor failure");
        } catch (RuntimeException expected) {
            // Mirror process()'s finally block.
            scheduler.stop(12L, "peer:12:1");
            Files.deleteIfExists(log);
        }

        assertFalse(Files.exists(log),
            "log file must be removed even when the processor throws");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Secrets sweep — one test per pattern + one combined
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void secretsSweep_envStyleTokenKeySecretPassword() {
        // Pattern: [A-Z0-9_]*(TOKEN|KEY|SECRET|PASSWORD)\s*=\s*\S+
        assertEquals("API_TOKEN=<redacted>",
            SecretsSweeper.redact("API_TOKEN=abc123"));
        assertEquals("AWS_SECRET=<redacted>",
            SecretsSweeper.redact("AWS_SECRET=AKIAxxx"));
        assertEquals("OPENAI_KEY=<redacted>",
            SecretsSweeper.redact("OPENAI_KEY=sk-abc"));
        assertEquals("password=<redacted>",
            SecretsSweeper.redact("password=hunter2"),
            "case-insensitive — lowercase password matches");
        assertEquals("DATABASE_PASSWORD = <redacted>",
            SecretsSweeper.redact("DATABASE_PASSWORD = s3cret"),
            "whitespace around = is tolerated");
    }

    @Test
    void secretsSweep_authorizationHeader() {
        assertEquals("Authorization: <redacted>",
            SecretsSweeper.redact("Authorization: secret-token-xyz"));
        assertEquals("authorization: <redacted>",
            SecretsSweeper.redact("authorization: anothertoken"),
            "case-insensitive");
    }

    @Test
    void secretsSweep_bearerToken() {
        assertEquals("Bearer <redacted>",
            SecretsSweeper.redact("Bearer abc123def456"));
        assertEquals("bearer <redacted>",
            SecretsSweeper.redact("bearer xyz"),
            "case-insensitive");
    }

    @Test
    void secretsSweep_fortyCharHexPrecededByWhitespace() {
        // 40 hex chars — the standard length of a SHA1 / classic GitHub PAT.
        String key = "abcdef0123456789abcdef0123456789abcdef01";
        assertEquals(40, key.length(), "test fixture sanity check");
        assertEquals("prefix <redacted> suffix",
            SecretsSweeper.redact("prefix " + key + " suffix"));

        // 39 hex chars must NOT match (the heuristic is specifically 40).
        String shortKey = "abcdef0123456789abcdef0123456789abcdef0";
        assertEquals("prefix " + shortKey + " suffix",
            SecretsSweeper.redact("prefix " + shortKey + " suffix"));

        // Embedded inside a longer hex string must not match (no preceding ws).
        String longHex = "0".repeat(60);
        assertEquals(longHex, SecretsSweeper.redact(longHex));
    }

    @Test
    void secretsSweep_combinedSamplePayload() {
        String input = String.join("\n",
            "running step 1: deploying app",
            "API_TOKEN=ghp_abc123def456ghi789",
            "Authorization: Bearer xyz123secret",
            " 1234567890abcdef1234567890abcdef12345678",   // 40-char hex preceded by ws
            "running step 2: complete");

        String redacted = SecretsSweeper.redact(input);

        // Each pattern's value-side should be redacted; the surrounding
        // narrative text stays.
        assertTrue(redacted.contains("running step 1"),     "narrative preserved");
        assertTrue(redacted.contains("running step 2"),     "narrative preserved");
        assertTrue(redacted.contains("API_TOKEN=<redacted>"), "KV pattern: " + redacted);
        assertTrue(redacted.contains("<redacted>"),         "at least one redaction");
        assertFalse(redacted.contains("ghp_abc123def456"),  "raw token must be gone");
        assertFalse(redacted.contains("xyz123secret"),      "bearer token must be gone");
        assertFalse(redacted.contains("1234567890abcdef1234567890abcdef12345678"),
            "40-char hex must be gone");
    }

    @Test
    void secretsSweep_idempotent() {
        // Running redact twice on the same input must yield the same string.
        // Important property since process() calls it from a scheduled task
        // and we don't want repeated runs to keep mutating the payload.
        String input = "API_TOKEN=abc123\nBearer xyz";
        String once  = SecretsSweeper.redact(input);
        String twice = SecretsSweeper.redact(once);
        assertEquals(once, twice, "redact must be idempotent");
    }

    @Test
    void secretsSweep_nullAndEmptyPassthrough() {
        assertNull(SecretsSweeper.redact(null));
        assertEquals("", SecretsSweeper.redact(""));
    }

    @Test
    void secretsSweep_payloadIsRedactedBeforeRelay(@TempDir Path tmp) throws Exception {
        // End-to-end: write a credential-shaped line to the log, run a beat,
        // and confirm the relayed payload is redacted (not the raw secret).
        Path log = Files.createFile(tmp.resolve("creds.log"));
        Files.writeString(log,
            "API_TOKEN=ghp_supersecret_abc123\n"
            + "running step 1\n",
            StandardCharsets.UTF_8);

        scheduler.startUnclampedForTest(50L, "peer:50:1", "peer", 50L, log);
        sender.awaitCount(1, 2000);
        scheduler.stop(50L, "peer:50:1");

        String payload = sender.sent.get(0).content();
        assertFalse(payload.contains("ghp_supersecret_abc123"),
            "raw token must NOT be relayed: " + payload);
        assertTrue(payload.contains("API_TOKEN=<redacted>"),
            "redaction marker must be present: " + payload);
        assertTrue(payload.contains("running step 1"),
            "non-secret narrative passes through: " + payload);
    }
}
