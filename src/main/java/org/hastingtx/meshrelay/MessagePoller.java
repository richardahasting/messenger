package org.hastingtx.meshrelay;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * Background polling loop — queries OpenBrain every 10 minutes for pending
 * messages addressed to this node and processes them.
 *
 * This is the reliability safety net. Wake-up pings are the fast path;
 * this poll is the guaranteed catch-up path.
 *
 * Concurrency guarantees:
 *
 *   1. Poll deduplication (coalescing trigger):
 *      Only one poll() runs at a time. If wake() is called while a poll
 *      is already in-flight, a flag is set so exactly one more poll runs
 *      after the current one completes. N simultaneous wake-ups collapse
 *      into at most 2 sequential runs — no double-processing.
 *
 *   2. Per-thread serialization:
 *      Messages belonging to the same conversation thread (same thread_id)
 *      are processed one at a time, in arrival order. This ensures an agent
 *      processing reply B always sees reply A fully committed first.
 *      Different threads are processed concurrently with no ordering
 *      constraint between them.
 */
public class MessagePoller implements Runnable {

    private static final Logger   log           = Logger.getLogger(MessagePoller.class.getName());
    private static final Duration POLL_INTERVAL = Duration.ofMinutes(10);

    private final PeerConfig       config;
    private final OpenBrainStore   brain;
    private final MessageProcessor processor;

    private volatile boolean running  = true;
    private Instant lastPollTime      = Instant.EPOCH;
    private int     totalProcessed    = 0;

    // ── Concurrency controls ──────────────────────────────────────────────

    /** How long to wait for a per-thread lock before giving up on this message. */
    private static final long THREAD_LOCK_TIMEOUT_MINUTES = 15;

    /** True while a poll() run is executing. */
    private final AtomicBoolean pollInFlight = new AtomicBoolean(false);

    /**
     * Set to true when wake() fires during an in-flight poll.
     * Causes one additional poll to run immediately after the current one
     * finishes — collapses N concurrent wake-ups into at most 2 sequential runs.
     */
    private final AtomicBoolean pollPending = new AtomicBoolean(false);

    /**
     * Per-thread locks: ensures messages with the same thread_id are processed
     * sequentially. Locks are created on demand and removed after use to avoid
     * unbounded map growth.
     */
    private final ConcurrentHashMap<Long, ReentrantLock> threadLocks = new ConcurrentHashMap<>();

    public MessagePoller(PeerConfig config, OpenBrainStore brain, MessageProcessor processor) {
        this.config    = config;
        this.brain     = brain;
        this.processor = processor;
    }

    /** Start the polling loop on a virtual thread. Returns immediately. */
    public Thread startInBackground() {
        Thread t = Thread.ofVirtual()
            .name("message-poller")
            .start(this);
        log.info("Message poller started — interval=" + POLL_INTERVAL.toMinutes() + "m"
            + " node=" + config.nodeName);
        return t;
    }

    @Override
    public void run() {
        // Poll immediately on startup to catch messages that arrived while
        // the daemon was down — don't wait the full 10 minutes first.
        triggerPoll();

        while (running) {
            try {
                Thread.sleep(POLL_INTERVAL);
                triggerPoll();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Trigger an immediate poll — called when a wake-up ping arrives.
     * Safe to call concurrently from any number of threads.
     */
    public void wake() {
        log.info("Wake-up received — triggering immediate poll");
        Thread.ofVirtual().start(this::triggerPoll);
    }

    /**
     * Coalescing entry point for all poll triggers (scheduled and wake-up).
     *
     * If no poll is running: runs poll() immediately.
     * If a poll is already running: sets pollPending=true so the in-flight
     *   poll will run one more time after it finishes. Does NOT start a
     *   second concurrent poll.
     */
    private void triggerPoll() {
        if (!pollInFlight.compareAndSet(false, true)) {
            // A poll is already running — request a follow-up run
            pollPending.set(true);
            log.fine("Poll already in-flight — queued one follow-up run");
            return;
        }
        try {
            poll();
            // Drain the pending flag: if wake() fired during our run, do one more pass
            while (pollPending.compareAndSet(true, false)) {
                poll();
            }
        } finally {
            pollInFlight.set(false);
        }
    }

    /**
     * Execute one poll cycle: fetch pending messages, process each in
     * per-thread order, mark archived. Errors in individual messages are
     * logged and skipped so a bad message never stops the rest.
     */
    private void poll() {
        lastPollTime = Instant.now();
        log.fine("Polling OpenBrain for pending messages — node=" + config.nodeName);

        List<OpenBrainStore.PendingMessage> pending;
        try {
            pending = brain.pollPendingMessages(config.nodeName);
        } catch (Exception e) {
            log.warning("Poll failed — OpenBrain unreachable: " + e.getMessage());
            return;
        }

        if (pending.isEmpty()) {
            log.fine("Poll complete — no pending messages");
            return;
        }

        log.info("Poll found " + pending.size() + " pending message(s)");

        for (OpenBrainStore.PendingMessage msg : pending) {
            processWithThreadLock(msg);
        }
    }

    /**
     * Acquire the per-thread lock before processing, then release it.
     * Messages with different thread_ids run concurrently.
     * Messages with the same thread_id are serialized.
     */
    private void processWithThreadLock(OpenBrainStore.PendingMessage msg) {
        long threadId = msg.threadId();
        ReentrantLock lock = threadLocks.computeIfAbsent(threadId, id -> new ReentrantLock());
        boolean acquired;
        try {
            acquired = lock.tryLock(THREAD_LOCK_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warning("Interrupted waiting for thread lock thread_id=" + threadId
                + " — skipping, will retry next poll");
            return;
        }

        if (!acquired) {
            // The previous message in this thread has been running for 15+ minutes.
            // Leave this message unarchived so it will be retried next poll.
            log.warning("Thread lock timeout (>" + THREAD_LOCK_TIMEOUT_MINUTES + "m) for thread_id="
                + threadId + " from=" + msg.fromNode()
                + " — skipping this cycle, will retry next poll");
            return;
        }

        try {
            processor.process(msg);
            brain.markArchived(msg.thoughtId());
            totalProcessed++;
            log.info("Processed message thread_id=" + threadId
                + " from=" + msg.fromNode()
                + " total_processed=" + totalProcessed);
        } catch (Exception e) {
            // Do NOT mark archived — message will be retried next poll
            log.warning("Failed to process message thread_id=" + threadId
                + ": " + e.getMessage() + " — will retry next poll");
        } finally {
            lock.unlock();
            // Remove lock if no other thread is waiting on it
            threadLocks.remove(threadId, lock);
        }
    }

    public void stop() { running = false; }

    public Instant getLastPollTime()   { return lastPollTime; }
    public int     getTotalProcessed() { return totalProcessed; }
}
