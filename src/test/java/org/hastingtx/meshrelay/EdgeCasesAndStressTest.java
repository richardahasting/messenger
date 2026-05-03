package org.hastingtx.meshrelay;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static java.time.Duration.ofSeconds;

/**
 * Adversarial / boundary / stress tests for v1.2.0. The goal is to push the
 * loop-prevention, dedup, secrets-sweep, and progress-tail mechanisms to
 * limits that should never occur in real traffic — so a regression that
 * narrows the safe envelope shows up here first.
 *
 * <p>Organized by component. Each nested class targets one piece of the
 * v1.2.0 protocol surface; see docs/protocol-v1.2.md for the contract being
 * exercised.
 */
class EdgeCasesAndStressTest {

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    class DedupCacheStress {

        @Test
        void rejectsZeroOrNegativeCap() {
            assertThrows(IllegalArgumentException.class, () -> new DedupCache(0));
            assertThrows(IllegalArgumentException.class, () -> new DedupCache(-1));
            assertThrows(IllegalArgumentException.class, () -> new DedupCache(Integer.MIN_VALUE));
        }

        @Test
        void capOfOneEvictsOnSecondInsert() {
            DedupCache c = new DedupCache(1);
            c.put(key("n", 1, "a"), resp("a"));
            c.put(key("n", 1, "b"), resp("b"));
            // Eviction trims to ~90% of cap, but cap=1 means the bulk-evict
            // target is 0 — so the cache may briefly hold 0 or 1 entries.
            // Either is acceptable; the contract is "doesn't grow unbounded".
            assertTrue(c.size() <= 1, "size after eviction must respect cap");
        }

        @Test
        void evictionTrimsToTargetWhenExceedingDefaultCap() {
            DedupCache c = new DedupCache();   // 10_000
            for (int i = 0; i < 11_000; i++) {
                c.put(key("n", 0, "s" + i), resp("r" + i));
            }
            // Bulk eviction trims to ~90% of cap. Allow some slack — the
            // eviction snapshot is best-effort under concurrent puts.
            assertTrue(c.size() <= 10_000,
                "size after eviction must not exceed cap, got " + c.size());
            assertTrue(c.size() >= 8_500,
                "eviction must not over-trim; got " + c.size());
        }

        @Test
        void manyConcurrentWritersOfTheSameKey() throws Exception {
            // 1000 threads × 100 puts of the SAME key — cache must end with
            // exactly one entry, no exception, no value corruption.
            DedupCache c = new DedupCache();
            DedupCache.DedupKey k = key("n", 1, "race");
            int threads = 1000, perThread = 100;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            ExecutorService es = Executors.newFixedThreadPool(64);
            for (int t = 0; t < threads; t++) {
                final int id = t;
                es.submit(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < perThread; j++) {
                            c.put(k, resp("from-" + id + "-" + j));
                        }
                    } catch (InterruptedException ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));
            es.shutdown();

            assertEquals(1, c.size(), "single key must produce single entry");
            assertNotNull(c.get(k));
        }

        @Test
        void manyConcurrentWritersOfDistinctKeys() throws Exception {
            // 50 threads × 200 distinct keys = 10K — at exactly default cap.
            DedupCache c = new DedupCache();
            int threads = 50, perThread = 200;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            ExecutorService es = Executors.newFixedThreadPool(16);
            for (int t = 0; t < threads; t++) {
                final int id = t;
                es.submit(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < perThread; j++) {
                            c.put(key("n" + id, id, "s" + j), resp("r"));
                        }
                    } catch (InterruptedException ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));
            es.shutdown();

            // 10K writes; cap is 10K — eviction may or may not have triggered.
            assertTrue(c.size() <= 10_000);
            assertTrue(c.size() >= 8_500);
        }

        @Test
        void nullSeqIdAndEmptySeqIdAreDistinctKeys() {
            // The DedupKey record permits null components — null and "" must
            // not coalesce to the same key, otherwise a sender sending an
            // explicit-empty seq could replay a sender omitting seq.
            DedupCache c = new DedupCache();
            DedupCache.DedupKey kNull = new DedupCache.DedupKey("n", 1, null);
            DedupCache.DedupKey kEmpty = new DedupCache.DedupKey("n", 1, "");
            c.put(kNull, resp("null-seq"));
            c.put(kEmpty, resp("empty-seq"));
            assertEquals(2, c.size());
            assertEquals("null-seq", c.get(kNull).payload());
            assertEquals("empty-seq", c.get(kEmpty).payload());
        }

        @Test
        void negativeThreadIdIsAllowedAndKeyed() {
            DedupCache c = new DedupCache();
            DedupCache.DedupKey kNeg = key("n", -42, "s");
            DedupCache.DedupKey kPos = key("n",  42, "s");
            c.put(kNeg, resp("neg"));
            c.put(kPos, resp("pos"));
            assertEquals(2, c.size());
            assertEquals("neg", c.get(kNeg).payload());
            assertEquals("pos", c.get(kPos).payload());
        }

        @Test
        void absurdlyLongSeqIdIsAcceptedAndRetrievable() {
            // 100K-char seqId — the protocol doesn't cap seq_id length and
            // OpenBrain stores arbitrary strings. Cache must not choke.
            DedupCache c = new DedupCache();
            String huge = "x".repeat(100_000);
            DedupCache.DedupKey k = key("n", 1, huge);
            c.put(k, resp("ok"));
            assertEquals(1, c.size());
            assertEquals("ok", c.get(k).payload());
        }

        @Test
        void sentinelDistinguishesProcessedNoResponseFromMiss() {
            DedupCache c = new DedupCache();
            DedupCache.DedupKey k = key("n", 1, "s");
            assertNull(c.get(k));                        // miss
            c.putSentinel(k);
            DedupCache.CachedResponse cached = c.get(k);
            assertNotNull(cached);                       // hit, but...
            assertFalse(cached.hasResponse());           // ...no payload
        }

        @Test
        void emergencyHardLoad_oneHundredThousandPuts() throws Exception {
            // 10× the default cap — confirms eviction runs many times without
            // pathological slowdown. Should finish well under 5s on any
            // modern machine; a regression to per-put LRU could blow this up.
            DedupCache c = new DedupCache();
            assertTimeoutPreemptively(ofSeconds(5), () -> {
                for (int i = 0; i < 100_000; i++) {
                    c.put(key("n", i, "s"), resp("r"));
                }
            });
            assertTrue(c.size() <= 10_000);
        }

        // helpers
        private DedupCache.DedupKey key(String from, long thread, String seq) {
            return new DedupCache.DedupKey(from, thread, seq);
        }
        private DedupCache.CachedResponse resp(String payload) {
            return new DedupCache.CachedResponse("reply", payload, "1", java.time.Instant.now());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    class SecretsSweeperEdge {

        @Test
        void nullEmptyAndWhitespaceInputs() {
            assertNull(SecretsSweeper.redact(null));
            assertEquals("", SecretsSweeper.redact(""));
            assertEquals("   ", SecretsSweeper.redact("   "));
            assertEquals("\n\n\t", SecretsSweeper.redact("\n\n\t"));
        }

        @Test
        void patternAtStartOfInput() {
            assertEquals("API_TOKEN=<redacted>",
                SecretsSweeper.redact("API_TOKEN=abcdef123456"));
        }

        @Test
        void patternAtEndOfInputWithoutTrailingNewline() {
            String in = "log line one\nlog line two\nPASSWORD=secret";
            String out = SecretsSweeper.redact(in);
            assertTrue(out.endsWith("PASSWORD=<redacted>"), "got: " + out);
        }

        @Test
        void multiplePatternsInOneLineAllRedacted() {
            String in = "TOKEN=a SECRET=b PASSWORD=c";
            String out = SecretsSweeper.redact(in);
            assertFalse(out.contains("=a"), out);
            assertFalse(out.contains("=b"), out);
            assertFalse(out.contains("=c"), out);
            // Three redactions
            assertEquals(3, countOccurrences(out, "<redacted>"));
        }

        @Test
        void authorizationBearerBothHalvesRedacted() {
            String in = "Authorization: Bearer abc123token";
            String out = SecretsSweeper.redact(in);
            // Per the class doc, BEARER runs first then AUTHORIZATION over
            // the residual. Either way, the actual token must NOT appear.
            assertFalse(out.contains("abc123token"),
                "raw token leaked: " + out);
        }

        @Test
        void falsePositiveProseMentioningPassword() {
            // Word "password" appears but no = follows. Must NOT redact.
            String in = "Please reset the user password by clicking the link.";
            assertEquals(in, SecretsSweeper.redact(in));
        }

        @Test
        void hexBoundary39CharsNoMatch() {
            String s39 = "deadbeef".repeat(4) + "1234567";  // 39 chars
            assertEquals(39, s39.length());
            String in = " " + s39 + " trailing";
            assertEquals(in, SecretsSweeper.redact(in));
        }

        @Test
        void hexBoundary40CharsWithLeadingWhitespaceMatches() {
            String s40 = "deadbeef".repeat(5);  // 40 chars
            assertEquals(40, s40.length());
            String out = SecretsSweeper.redact(" " + s40 + " trailing");
            assertTrue(out.contains("<redacted>"), out);
            assertFalse(out.contains(s40), "raw hex leaked: " + out);
        }

        @Test
        void hexBoundary40CharsWithoutLeadingWhitespaceDoesNotMatch() {
            // Pattern requires whitespace lookbehind. Hex glued to other
            // text isn't a key — could be a hash, a path component, etc.
            String s40 = "deadbeef".repeat(5);
            String in = "abc" + s40 + " trailing";
            assertEquals(in, SecretsSweeper.redact(in));
        }

        @Test
        void hexBoundary41CharsDoesNotMatch() {
            String s41 = "deadbeef".repeat(5) + "f";  // 41 chars
            String in = " " + s41 + " trailing";
            assertEquals(in, SecretsSweeper.redact(in));
        }

        @Test
        void redactIsIdempotent() {
            String original = "Authorization: Bearer xyz\nAPI_KEY=foo";
            String once = SecretsSweeper.redact(original);
            String twice = SecretsSweeper.redact(once);
            assertEquals(once, twice, "redact() must be idempotent");
        }

        @Test
        void caseVariantsAllRedacted() {
            assertTrue(SecretsSweeper.redact("password=foo").contains("<redacted>"));
            assertTrue(SecretsSweeper.redact("PASSWORD=foo").contains("<redacted>"));
            assertTrue(SecretsSweeper.redact("Password=foo").contains("<redacted>"));
            assertTrue(SecretsSweeper.redact("PaSsWoRd=foo").contains("<redacted>"));
        }

        @Test
        void unicodeInSecretValueStillRedacted() {
            // Emoji + RTL marker in secret value — value-side regex is \S+
            // which is unicode-aware on JVM by default.
            String in = "API_TOKEN=🔑‮secretValue";
            String out = SecretsSweeper.redact(in);
            assertFalse(out.contains("secretValue"), out);
            assertTrue(out.contains("<redacted>"));
        }

        @Test
        void multiLineInputSecretMidStream() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 100; i++) sb.append("log line ").append(i).append("\n");
            sb.insert(sb.indexOf("log line 5"), "DB_PASSWORD=hunter2\n");
            String out = SecretsSweeper.redact(sb.toString());
            assertFalse(out.contains("hunter2"));
            assertTrue(out.contains("DB_PASSWORD=<redacted>"));
        }

        @Test
        void onePerformantMegabyteOfProseWithSecretAtEnd() {
            // 1 MB of innocent text + a secret at the very end. Must complete
            // in well under 1 s; a catastrophic-backtracking regex would not.
            StringBuilder sb = new StringBuilder(1_100_000);
            String filler = "Lorem ipsum dolor sit amet, consectetur. ";
            while (sb.length() < 1_000_000) sb.append(filler);
            sb.append(" GH_TOKEN=ghp_abcdefghijklmnopqrstuvwxyz0123456789");
            String input = sb.toString();
            assertTimeoutPreemptively(ofSeconds(2), () -> {
                String out = SecretsSweeper.redact(input);
                assertFalse(out.contains("ghp_abcdef"), "raw token leaked");
                assertTrue(out.endsWith("GH_TOKEN=<redacted>"));
            });
        }

        private int countOccurrences(String haystack, String needle) {
            int n = 0, idx = 0;
            while ((idx = haystack.indexOf(needle, idx)) != -1) { n++; idx += needle.length(); }
            return n;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    class ProgressBeatBoundaries {

        @Test
        void clampNegativeBecomesMinimum() {
            assertEquals(30, ProgressBeatScheduler.clampInterval(-1));
            assertEquals(30, ProgressBeatScheduler.clampInterval(Integer.MIN_VALUE));
        }

        @Test
        void clampZeroBecomesMinimum() {
            assertEquals(30, ProgressBeatScheduler.clampInterval(0));
        }

        @Test
        void clampJustBelowMinimumBecomesMinimum() {
            assertEquals(30, ProgressBeatScheduler.clampInterval(29));
        }

        @Test
        void clampAtMinimumIsUnchanged() {
            assertEquals(30, ProgressBeatScheduler.clampInterval(30));
        }

        @Test
        void clampWithinRangeIsUnchanged() {
            assertEquals(60, ProgressBeatScheduler.clampInterval(60));
            assertEquals(90, ProgressBeatScheduler.clampInterval(90));
        }

        @Test
        void clampAtMaximumIsUnchanged() {
            assertEquals(120, ProgressBeatScheduler.clampInterval(120));
        }

        @Test
        void clampJustAboveMaximumBecomesMaximum() {
            assertEquals(120, ProgressBeatScheduler.clampInterval(121));
        }

        @Test
        void clampAbsurdlyHighBecomesMaximum() {
            assertEquals(120, ProgressBeatScheduler.clampInterval(Integer.MAX_VALUE));
            assertEquals(120, ProgressBeatScheduler.clampInterval(86_400)); // a day
        }

        // ─── readTail boundaries ────────────────────────────────────────

        @Test
        void readTailOfNullPathReturnsEmpty() {
            assertEquals("", ProgressBeatScheduler.readTail(null, 10, 4096));
        }

        @Test
        void readTailOfNonexistentFileReturnsEmpty(@TempDir Path tmp) {
            Path missing = tmp.resolve("does-not-exist.log");
            assertEquals("", ProgressBeatScheduler.readTail(missing, 10, 4096));
        }

        @Test
        void readTailOfEmptyFileReturnsEmpty(@TempDir Path tmp) throws Exception {
            Path empty = Files.createFile(tmp.resolve("empty.log"));
            assertEquals("", ProgressBeatScheduler.readTail(empty, 10, 4096));
        }

        @Test
        void readTailOfSingleByteFile(@TempDir Path tmp) throws Exception {
            Path f = tmp.resolve("one.log");
            Files.writeString(f, "x");
            assertEquals("x", ProgressBeatScheduler.readTail(f, 10, 4096));
        }

        @Test
        void readTailRespectsByteCapOnFileLargerThanCap(@TempDir Path tmp) throws Exception {
            // 8 KB of distinct lines → tail must read at most 4 KB (default cap).
            Path f = tmp.resolve("big.log");
            StringBuilder sb = new StringBuilder();
            int line = 0;
            while (sb.length() < 8 * 1024) {
                sb.append("line-").append(line++).append("\n");
            }
            Files.writeString(f, sb.toString());
            String tail = ProgressBeatScheduler.readTail(f, 1000, 4096);
            assertTrue(tail.length() <= 4096,
                "tail must respect byte cap, got " + tail.length() + " bytes");
            assertTrue(tail.contains("line-" + (line - 1)),
                "tail must include the actually-last line");
        }

        @Test
        void readTailRespectsLineCapWhenFileFitsInBytes(@TempDir Path tmp) throws Exception {
            Path f = tmp.resolve("many-short-lines.log");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 100; i++) sb.append("L").append(i).append("\n");
            Files.writeString(f, sb.toString());

            String tail = ProgressBeatScheduler.readTail(f, 10, 4096);
            assertEquals(10, tail.split("\n", -1).length,
                "must return exactly TAIL_MAX_LINES lines");
            assertTrue(tail.startsWith("L90"));
            assertTrue(tail.endsWith("L99"));
        }

        @Test
        void readTailWithMixedLineSeparators(@TempDir Path tmp) throws Exception {
            // \r, \n, \r\n all in one file. Java's \\R matches all of them —
            // this guards against a regression that uses just \n.
            Path f = tmp.resolve("mixed.log");
            Files.writeString(f, "unix\nmac\rwindows\r\nlast", StandardCharsets.UTF_8);
            String tail = ProgressBeatScheduler.readTail(f, 10, 4096);
            assertTrue(tail.contains("unix"));
            assertTrue(tail.contains("mac"));
            assertTrue(tail.contains("windows"));
            assertTrue(tail.contains("last"));
        }

        @Test
        void readTailOfBinaryDataDoesNotCrash(@TempDir Path tmp) throws Exception {
            // Binary blob with NUL bytes — a tool that misuses
            // MESSENGER_PROGRESS_LOG could accidentally write binary. Tail
            // must not throw; payload may be lossy but daemon stays up.
            Path f = tmp.resolve("binary.log");
            byte[] bin = new byte[2048];
            for (int i = 0; i < bin.length; i++) bin[i] = (byte) (i % 256);
            Files.write(f, bin);
            assertDoesNotThrow(() ->
                ProgressBeatScheduler.readTail(f, 10, 4096));
        }

        @Test
        void readTailOfFileWithSingleHugeLineExceedingCap(@TempDir Path tmp) throws Exception {
            // A single 10 KB line — no newlines at all. Tail must respect
            // the byte cap and return at most 4 KB worth of the line.
            Path f = tmp.resolve("one-huge-line.log");
            String huge = "X".repeat(10 * 1024);
            Files.writeString(f, huge);
            String tail = ProgressBeatScheduler.readTail(f, 10, 4096);
            assertTrue(tail.length() <= 4096,
                "tail must cap absurd single-line files; got " + tail.length());
            assertFalse(tail.isEmpty());
        }

        @Test
        void readTailOfFileWithOnlyNewlinesReturnsEmpty(@TempDir Path tmp) throws Exception {
            // A log file consisting only of empty lines should be treated
            // the same as an empty file — caller substitutes "(no log activity)".
            Path f = tmp.resolve("blank-lines.log");
            Files.writeString(f, "\n\n\n\n");
            assertEquals("", ProgressBeatScheduler.readTail(f, 10, 4096));
        }

        // ─── start() / stop() concurrency ───────────────────────────────

        @Test
        void startStopOneHundredConcurrentBeatsWithoutDeadlock() throws Exception {
            // 100 simultaneous start() calls on distinct (thread, seq) pairs,
            // followed by 100 stop()s. Must not deadlock, leak threads, or
            // throw. Uses startUnclampedForTest with 10ms cadence so the
            // beats fire during the test window.
            RecordingRelaySender sender = new RecordingRelaySender();
            ProgressBeatScheduler scheduler =
                new ProgressBeatScheduler(sender, "linuxserver");

            int n = 100;
            CountDownLatch ready = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(n);
            ExecutorService es = Executors.newFixedThreadPool(16);

            try (TempPaths paths = new TempPaths(n)) {
                for (int i = 0; i < n; i++) {
                    final int id = i;
                    es.submit(() -> {
                        try {
                            ready.await();
                            scheduler.startUnclampedForTest(
                                id, "s" + id, "originator", 10, paths.get(id));
                        } catch (InterruptedException ignored) {
                        } finally {
                            done.countDown();
                        }
                    });
                }
                ready.countDown();
                assertTrue(done.await(15, TimeUnit.SECONDS));

                // Let some beats fire.
                Thread.sleep(150);

                // Stop them all.
                for (int i = 0; i < n; i++) {
                    scheduler.stop(i, "s" + i);
                }
                scheduler.shutdown();
                es.shutdown();

                // At least one beat per starter — otherwise the scheduler
                // is wedged. Don't assert a tight count because the JVM
                // scheduler is lumpy under heavy contention.
                assertTrue(sender.calls.size() >= n,
                    "expected >= " + n + " beats, got " + sender.calls.size());
            }
        }

        @Test
        void stopOnUnknownKeyIsHarmless() {
            ProgressBeatScheduler scheduler =
                new ProgressBeatScheduler(RelaySender.NOOP, "linuxserver");
            assertDoesNotThrow(() -> scheduler.stop(999, "never-started"));
            assertDoesNotThrow(() -> scheduler.stop(-1, null));
            scheduler.shutdown();
        }

        @Test
        void doubleStopOnSameKeyIsHarmless(@TempDir Path tmp) throws Exception {
            ProgressBeatScheduler scheduler =
                new ProgressBeatScheduler(RelaySender.NOOP, "linuxserver");
            Path log = Files.createFile(tmp.resolve("d.log"));
            scheduler.startUnclampedForTest(1, "s", "from", 50, log);
            scheduler.stop(1, "s");
            assertDoesNotThrow(() -> scheduler.stop(1, "s"));   // second stop — no-op
            scheduler.shutdown();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Test-only helpers
    // ════════════════════════════════════════════════════════════════════════

    /** RelaySender stub that records every call without doing any I/O. */
    private static final class RecordingRelaySender implements RelaySender {
        final List<String> calls =
            Collections.synchronizedList(new java.util.ArrayList<>());
        @Override
        public boolean send(String to, String from, String content,
                            String kind, String inReplyTo,
                            String replyPolicy, long threadId) {
            calls.add(kind + " " + content);
            return true;
        }
    }

    /**
     * Allocates N temporary log-file paths under a single temp directory and
     * cleans up on close. Used by the concurrent-beats test which needs
     * distinct paths but a shared temp dir lifetime.
     */
    private static final class TempPaths implements AutoCloseable {
        private final Path dir;
        private final Path[] paths;
        TempPaths(int n) throws java.io.IOException {
            this.dir = Files.createTempDirectory("edge-test-");
            this.paths = new Path[n];
            for (int i = 0; i < n; i++) {
                paths[i] = Files.createFile(dir.resolve("log-" + i + ".log"));
            }
        }
        Path get(int i) { return paths[i]; }
        @Override
        public void close() throws java.io.IOException {
            try (var s = Files.walk(dir)) {
                s.sorted((a, b) -> b.toString().length() - a.toString().length())
                 .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            }
        }
    }
}
