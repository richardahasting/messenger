package org.hastingtx.meshrelay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DedupCache} — issue #16, v1.2 idempotent retransmit.
 *
 * Behaviour the cache must guarantee:
 *   - put/get round-trip is exact;
 *   - distinct (from, thread, seq) keys never collide;
 *   - concurrent puts/gets don't corrupt internal state;
 *   - the configured cap bounds memory growth.
 *
 * Spec: docs/protocol-v1.2.md § "Dedup cache" — full table.
 */
class DedupCacheTest {

    // ── put/get round-trip ───────────────────────────────────────────────────

    @Test
    void putGetRoundTripReturnsSameResponse() {
        DedupCache cache = new DedupCache();
        DedupCache.DedupKey key = new DedupCache.DedupKey("macmini", 42L, "macmini:42:1");
        DedupCache.CachedResponse resp = new DedupCache.CachedResponse(
            "reply", "907 ft, 99.8% full", "macmini:42:1", Instant.now());

        cache.put(key, resp);

        DedupCache.CachedResponse got = cache.get(key);
        assertNotNull(got, "get must return the cached entry");
        assertEquals("reply",                got.kind());
        assertEquals("907 ft, 99.8% full",   got.payload());
        assertEquals("macmini:42:1",         got.inReplyTo());
        assertEquals(resp.cachedAt(),        got.cachedAt());
        assertTrue(got.hasResponse(), "non-null payload counts as 'response present'");
    }

    @Test
    void getOnMissReturnsNull() {
        DedupCache cache = new DedupCache();
        DedupCache.DedupKey key = new DedupCache.DedupKey("macmini", 42L, "never-seen");
        assertNull(cache.get(key));
        assertFalse(cache.contains(key));
    }

    @Test
    void putOverwritesPreviousValue() {
        // The poller may put a sentinel first (after process()) and then the
        // processor overwrites with the real reply. Both ends must see the
        // latest value.
        DedupCache cache = new DedupCache();
        DedupCache.DedupKey key = new DedupCache.DedupKey("macmini", 42L, "macmini:42:1");

        cache.putSentinel(key);
        assertFalse(cache.get(key).hasResponse(), "sentinel has no response");

        cache.put(key, new DedupCache.CachedResponse(
            "reply", "real reply", "macmini:42:1", Instant.now()));
        assertEquals("real reply", cache.get(key).payload(),
            "real response must overwrite the sentinel");
        assertTrue(cache.get(key).hasResponse());
    }

    @Test
    void sentinelHelperStoresProcessedNoResponseMarker() {
        // putSentinel is the convenience used by MessagePoller after a
        // processor returns without producing an outbound. A duplicate then
        // hits the cache and is silently swallowed (no resend, no re-process).
        DedupCache cache = new DedupCache();
        DedupCache.DedupKey key = new DedupCache.DedupKey("macmini", 42L, "macmini:42:7");

        cache.putSentinel(key);

        DedupCache.CachedResponse got = cache.get(key);
        assertNotNull(got);
        assertNull(got.payload(), "sentinel payload is null");
        assertFalse(got.hasResponse());
    }

    // ── Distinct keys do not collide ─────────────────────────────────────────

    @Test
    void distinctKeysDoNotCollide() {
        // The triple (fromNode, threadId, seqId) is the dedup identity. Any
        // single field differing must yield a distinct cache slot.
        DedupCache cache = new DedupCache();

        DedupCache.DedupKey k1 = new DedupCache.DedupKey("macmini",     42L, "seq-A");
        DedupCache.DedupKey k2 = new DedupCache.DedupKey("macbook-air", 42L, "seq-A"); // different from
        DedupCache.DedupKey k3 = new DedupCache.DedupKey("macmini",     43L, "seq-A"); // different thread
        DedupCache.DedupKey k4 = new DedupCache.DedupKey("macmini",     42L, "seq-B"); // different seq

        cache.put(k1, new DedupCache.CachedResponse("reply", "v1", "seq-A", Instant.now()));
        cache.put(k2, new DedupCache.CachedResponse("reply", "v2", "seq-A", Instant.now()));
        cache.put(k3, new DedupCache.CachedResponse("reply", "v3", "seq-A", Instant.now()));
        cache.put(k4, new DedupCache.CachedResponse("reply", "v4", "seq-B", Instant.now()));

        assertEquals("v1", cache.get(k1).payload());
        assertEquals("v2", cache.get(k2).payload());
        assertEquals("v3", cache.get(k3).payload());
        assertEquals("v4", cache.get(k4).payload());
        assertEquals(4, cache.size(), "four distinct keys → four entries");
    }

    @Test
    void dedupKeyEqualityIsValueBased() {
        // Records get value-equality for free, but assert it explicitly so a
        // future refactor to a class doesn't silently break HashMap lookup.
        DedupCache.DedupKey a = new DedupCache.DedupKey("macmini", 42L, "seq-1");
        DedupCache.DedupKey b = new DedupCache.DedupKey("macmini", 42L, "seq-1");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    // ── Concurrent access does not corrupt ──────────────────────────────────

    @Test
    @Timeout(30)
    void concurrentAccessDoesNotCorrupt() throws Exception {
        // Spec test: spawn 50 threads, each doing put/get on overlapping keys.
        // Assert no exceptions and final state is consistent: every put we
        // observe must round-trip via get.
        //
        // Overlap: 10 distinct keys shared across all 50 threads. Each thread
        // does 1000 put+get cycles on randomly-chosen keys. Total: 50,000
        // operations against 10 contended slots — should expose any racey
        // map manipulation if it exists.
        DedupCache cache = new DedupCache();
        int threads = 50;
        int opsPerThread = 1000;
        int distinctKeys = 10;

        DedupCache.DedupKey[] keys = new DedupCache.DedupKey[distinctKeys];
        for (int i = 0; i < distinctKeys; i++) {
            keys[i] = new DedupCache.DedupKey("peer", 100L, "seq-" + i);
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int t = 0; t < threads; t++) {
            final int seed = t;
            pool.submit(() -> {
                try {
                    start.await();
                    java.util.Random rng = new java.util.Random(seed);
                    for (int op = 0; op < opsPerThread; op++) {
                        int idx = rng.nextInt(distinctKeys);
                        DedupCache.DedupKey k = keys[idx];
                        if (rng.nextBoolean()) {
                            cache.put(k, new DedupCache.CachedResponse(
                                "reply", "p" + seed + "o" + op, "irt", Instant.now()));
                        } else {
                            // get may return null (if not yet put) or any prior put — both fine
                            cache.get(k);
                        }
                    }
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS),
            "concurrent stress workload must finish within budget");
        assertTrue(errors.isEmpty(),
            "no exceptions allowed under concurrent access; saw: " + errors);

        // Final state: every distinct key must hold *some* value (every key
        // had a high probability of being put across 50,000 ops). The cache
        // size is at most distinctKeys.
        assertTrue(cache.size() <= distinctKeys,
            "size must be bounded by the number of distinct keys, was " + cache.size());
        for (DedupCache.DedupKey k : keys) {
            DedupCache.CachedResponse got = cache.get(k);
            // Each key was almost certainly put by at least one thread; even
            // if not, get must not throw and must return null cleanly.
            if (got != null) {
                assertEquals("reply", got.kind(),
                    "any value present must be a well-formed CachedResponse");
            }
        }
    }

    @Test
    @Timeout(10)
    void concurrentPutsOnSameKeyAllSucceedLastWriterWinsLogically() {
        // Different from the stress test above: focus on the contended-key
        // case. 100 threads racing to put the same key — every put must
        // succeed (no thrown exceptions); the final get must return one of
        // the values that was put.
        DedupCache cache = new DedupCache();
        DedupCache.DedupKey key = new DedupCache.DedupKey("contender", 1L, "race");

        int threads = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int seed = t;
            pool.submit(() -> {
                try {
                    start.await();
                    cache.put(key, new DedupCache.CachedResponse(
                        "reply", "writer-" + seed, "irt", Instant.now()));
                } catch (Throwable e) {
                    errors.incrementAndGet();
                }
            });
        }

        start.countDown();
        pool.shutdown();
        try {
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted");
        }

        assertEquals(0, errors.get(), "concurrent puts on the same key must not throw");
        DedupCache.CachedResponse got = cache.get(key);
        assertNotNull(got, "value must be present after the race");
        assertTrue(got.payload().startsWith("writer-"),
            "final value must be one of the racing writes, was: " + got.payload());
    }

    // ── Bounded growth (memory cap) ──────────────────────────────────────────

    @Test
    void capBoundsCacheSize() {
        // Acceptance: "Memory footprint stays bounded (cap or document why
        // bounded growth is fine)." Verify the cap actually fires and trims
        // back below the limit on overflow.
        int cap = 100;
        DedupCache cache = new DedupCache(cap);

        // Insert cap+50 entries with monotonically-advancing cachedAt so the
        // eviction order is deterministic (oldest-first by Instant).
        for (int i = 0; i < cap + 50; i++) {
            DedupCache.DedupKey k = new DedupCache.DedupKey("peer", 1L, "seq-" + i);
            // Force a distinct, ordered cachedAt by stepping forward 1ms per put.
            Instant t = Instant.ofEpochMilli(1_000_000L + i);
            cache.put(k, new DedupCache.CachedResponse("reply", "v" + i, "irt", t));
        }

        assertTrue(cache.size() <= cap,
            "cache size must not exceed cap (=" + cap + "), was " + cache.size());
        // The oldest entries (low i) are the ones we expect to be gone.
        DedupCache.DedupKey first = new DedupCache.DedupKey("peer", 1L, "seq-0");
        assertNull(cache.get(first), "oldest entry must have been evicted");
        // The most recent entry must still be present.
        DedupCache.DedupKey last = new DedupCache.DedupKey("peer", 1L, "seq-" + (cap + 49));
        assertNotNull(cache.get(last), "most-recent entry must still be present");
    }

    @Test
    void invalidCapacityRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DedupCache(0));
        assertThrows(IllegalArgumentException.class, () -> new DedupCache(-1));
    }

    @Test
    void defaultCapacityIsTenThousand() {
        // Sanity guard against silent retuning of the documented cap.
        assertEquals(10_000, DedupCache.DEFAULT_MAX_ENTRIES);
    }
}
