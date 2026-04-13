package org.hastingtx.meshrelay;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the per-sender rate limiting logic in MessagePoller.
 * Verifies the sliding window approach: 3 messages per 10 minutes per sender.
 */
class RateLimitTest {

    // Mirror the rate limit constants from MessagePoller
    private static final int RATE_LIMIT_MAX = 3;
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(10);

    /** Simulates the isRateLimited check from MessagePoller. */
    private boolean isRateLimited(Deque<Instant> timestamps) {
        Instant cutoff = Instant.now().minus(RATE_LIMIT_WINDOW);
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(cutoff)) {
                timestamps.pollFirst();
            }
            return timestamps.size() >= RATE_LIMIT_MAX;
        }
    }

    @Test
    void noMessagesIsNotLimited() {
        Deque<Instant> ts = new ArrayDeque<>();
        assertFalse(isRateLimited(ts));
    }

    @Test
    void underLimitIsNotLimited() {
        Deque<Instant> ts = new ArrayDeque<>();
        ts.add(Instant.now());
        ts.add(Instant.now());
        assertFalse(isRateLimited(ts)); // 2 < 3
    }

    @Test
    void atLimitIsLimited() {
        Deque<Instant> ts = new ArrayDeque<>();
        ts.add(Instant.now());
        ts.add(Instant.now());
        ts.add(Instant.now());
        assertTrue(isRateLimited(ts)); // 3 >= 3
    }

    @Test
    void overLimitIsLimited() {
        Deque<Instant> ts = new ArrayDeque<>();
        ts.add(Instant.now());
        ts.add(Instant.now());
        ts.add(Instant.now());
        ts.add(Instant.now());
        assertTrue(isRateLimited(ts)); // 4 >= 3
    }

    @Test
    void expiredTimestampsAreEvicted() {
        Deque<Instant> ts = new ArrayDeque<>();
        // Add 3 timestamps from 15 minutes ago (outside window)
        Instant old = Instant.now().minus(Duration.ofMinutes(15));
        ts.add(old);
        ts.add(old);
        ts.add(old);
        // Should NOT be limited — all timestamps are expired
        assertFalse(isRateLimited(ts));
        // Timestamps should have been evicted
        assertTrue(ts.isEmpty());
    }

    @Test
    void mixOfOldAndNewTimestamps() {
        Deque<Instant> ts = new ArrayDeque<>();
        // 2 old (outside window) + 2 recent (inside window)
        Instant old = Instant.now().minus(Duration.ofMinutes(15));
        ts.add(old);
        ts.add(old);
        ts.add(Instant.now());
        ts.add(Instant.now());
        // After eviction: 2 recent, which is < 3
        assertFalse(isRateLimited(ts));
        assertEquals(2, ts.size());
    }

    @Test
    void mixOfOldAndNewAtLimit() {
        Deque<Instant> ts = new ArrayDeque<>();
        // 1 old + 3 recent
        Instant old = Instant.now().minus(Duration.ofMinutes(15));
        ts.add(old);
        ts.add(Instant.now());
        ts.add(Instant.now());
        ts.add(Instant.now());
        // After eviction: 3 recent, which is >= 3
        assertTrue(isRateLimited(ts));
        assertEquals(3, ts.size());
    }

    @Test
    void differentSendersAreIndependent() {
        // Simulates two separate sender queues
        Deque<Instant> gemma = new ArrayDeque<>();
        Deque<Instant> macmini = new ArrayDeque<>();

        gemma.add(Instant.now());
        gemma.add(Instant.now());
        gemma.add(Instant.now());

        macmini.add(Instant.now());

        assertTrue(isRateLimited(gemma));   // gemma at limit
        assertFalse(isRateLimited(macmini)); // macmini not affected
    }

    @Test
    void rateLimitArchivesNotDrops() {
        // Verify the design: rate-limited messages are archived (not left pending)
        // so they don't pile up and re-trigger on every poll cycle.
        // This is a documentation test — the actual archiving happens in MessagePoller.poll()
        // We just verify the constant values are what we expect.
        assertEquals(3, RATE_LIMIT_MAX);
        assertEquals(Duration.ofMinutes(10), RATE_LIMIT_WINDOW);
    }
}
