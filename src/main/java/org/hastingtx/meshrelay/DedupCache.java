package org.hastingtx.meshrelay;

import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory dedup cache keyed on {@code (from_node, thread_id, seq_id)} so a
 * duplicate inbound replays the cached response without re-invoking the
 * processor. Makes retransmits idempotent even though the v1.2 protocol does
 * not perform automatic retries — see docs/protocol-v1.2.md § "Dedup cache"
 * and § "Receiver behavior" step 2.
 *
 * <p>Daemon-local. Lost on restart. Acceptable per the spec: OpenBrain's
 * {@code status=delivered} guard prevents the poller from re-picking-up a
 * processed message after restart, so the cache is only load-bearing within
 * a single daemon lifetime.
 *
 * <p>Bounded at {@link #DEFAULT_MAX_ENTRIES} entries. When the cap is exceeded
 * a one-shot bulk eviction trims back to ~90% capacity by removing the
 * oldest-cached entries first. Bulk eviction (rather than strict per-put LRU)
 * keeps the hot path branch-light: each duplicate-check is a single
 * {@code ConcurrentHashMap} read with no extra book-keeping.
 *
 * <p>The entry value distinguishes "processed and a response was sent" from
 * "processed with no outbound response" via the
 * {@link CachedResponse#hasResponse()} predicate. The latter is the spec's
 * "processed-no-resp" sentinel.
 */
public final class DedupCache {

    /** Hard ceiling on entry count. ~10K is comfortably above the working set
     *  the mesh produces in a day and well below any memory concern. */
    public static final int DEFAULT_MAX_ENTRIES = 10_000;

    /** Composite key: same triple the spec identifies. */
    public record DedupKey(String fromNode, long threadId, String seqId) {}

    /**
     * Snapshot of the response the daemon emitted for a given inbound. {@code
     * payload == null} means "processed but no response was sent" — duplicate
     * inbound matching that key is silently swallowed (no resend, no
     * re-process).
     */
    public record CachedResponse(String kind, String payload, String inReplyTo, Instant cachedAt) {
        /** Sentinel for "processed but no response was sent" — see class doc. */
        public static CachedResponse processedNoResponse() {
            return new CachedResponse(null, null, null, Instant.now());
        }
        /** True when an outbound response was recorded for this key. */
        public boolean hasResponse() { return payload != null; }
    }

    private final int maxEntries;
    private final ConcurrentMap<DedupKey, CachedResponse> store = new ConcurrentHashMap<>();

    public DedupCache() { this(DEFAULT_MAX_ENTRIES); }

    public DedupCache(int maxEntries) {
        if (maxEntries < 1) throw new IllegalArgumentException("maxEntries must be >= 1");
        this.maxEntries = maxEntries;
    }

    /** @return the cached response for this key, or {@code null} on miss. */
    public CachedResponse get(DedupKey key) { return store.get(key); }

    /**
     * Insert (or overwrite) the cached response for a key. Triggers a single
     * bulk eviction pass when the store first exceeds the cap.
     */
    public void put(DedupKey key, CachedResponse response) {
        store.put(key, response);
        if (store.size() > maxEntries) evictOldest();
    }

    /** Convenience: store the "processed but no response sent" sentinel. */
    public void putSentinel(DedupKey key) {
        put(key, CachedResponse.processedNoResponse());
    }

    public boolean contains(DedupKey key) { return store.containsKey(key); }

    public int size() { return store.size(); }

    /**
     * Remove enough oldest-cached entries to bring size down to ~90% of cap.
     * Single-pass scan; concurrent puts during eviction are safe (the
     * snapshot is best-effort — we may evict a slightly-fresher key than the
     * truly-oldest if the race is unlucky, which is acceptable for an
     * in-memory dedup hint).
     */
    private void evictOldest() {
        int target = (int) (maxEntries * 0.9);
        int toRemove = store.size() - target;
        if (toRemove <= 0) return;
        store.entrySet().stream()
            .sorted(Comparator.comparing(e -> e.getValue().cachedAt()))
            .limit(toRemove)
            .map(Map.Entry::getKey)
            .forEach(store::remove);
    }
}
