package org.hastingtx.meshrelay;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
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
 *   2. Per-thread serialization with cross-thread concurrency:
 *      Messages belonging to the same conversation thread (same thread_id)
 *      are processed one at a time, in arrival order. This ensures an agent
 *      processing reply B always sees reply A fully committed first.
 *      Different threads are processed concurrently — each message is handed
 *      to its own virtual thread, so a long-running task on one thread does
 *      NOT block other threads (or the poll loop itself). This is the fix for
 *      messenger#27, where a multi-minute project agent on one thread left an
 *      unrelated connectivity test (thread 812) stuck pending for the full
 *      claude timeout. Total concurrency is bounded by a semaphore
 *      (config.maxConcurrentProcessing) so a burst can't exhaust the machine.
 *
 *   3. Dispatch is non-blocking:
 *      poll() claims each message into an in-flight set and dispatches it,
 *      then returns immediately. The in-flight set prevents a subsequent
 *      overlapping poll from re-dispatching a message before it has been
 *      marked delivered.
 */
public class MessagePoller implements Runnable {

    private static final Logger   log           = Logger.getLogger(MessagePoller.class.getName());
    static final Duration POLL_INTERVAL = Duration.ofMinutes(10);

    private final PeerConfig       config;
    private final OpenBrainStore   brain;
    private final MessageProcessor processor;

    private volatile boolean running  = true;
    // lastPollTime is written by the poll loop and read by the health endpoint;
    // totalProcessed is now incremented from many processing virtual threads
    // (messenger#27) — both must be safe for cross-thread visibility.
    private volatile Instant lastPollTime = Instant.EPOCH;
    private final java.util.concurrent.atomic.AtomicInteger totalProcessed =
        new java.util.concurrent.atomic.AtomicInteger(0);

    // ── Concurrency controls ──────────────────────────────────────────────

    /** How long to wait for a per-thread lock before giving up on this message. */
    private static final long THREAD_LOCK_TIMEOUT_MINUTES = 12;

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

    /**
     * Message ids currently dispatched to a processing virtual thread (claimed
     * but not yet completed). Guards against an overlapping poll re-dispatching
     * the same message in the window before markDelivered() lands. Entries are
     * removed when processing finishes (success, failure, or dead-letter).
     */
    private final Set<Integer> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * Bounds the number of messages processed concurrently. Each permit gates
     * one {@code claude -p} subprocess. Sized from config.maxConcurrentProcessing.
     */
    private final Semaphore processingSlots;

    // ── Per-sender rate limiting ──────────────────────────────────────────
    // Prevents chatty agents (e.g. Gemma hallucinating tasks) from flooding
    // the processor. 3 messages per 10 minutes per sender.

    private static final int    RATE_LIMIT_MAX     = 3;
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(10);

    /** sender → timestamps of recently processed messages (sliding window). */
    private final ConcurrentHashMap<String, Deque<Instant>> senderTimestamps = new ConcurrentHashMap<>();

    public MessagePoller(PeerConfig config, OpenBrainStore brain, MessageProcessor processor) {
        this.config    = config;
        this.brain     = brain;
        this.processor = processor;
        int slots = Math.max(1, config.maxConcurrentProcessing);
        // Fair semaphore: permits are granted in request order so a short task
        // that arrived first isn't starved behind later long ones.
        this.processingSlots = new Semaphore(slots, true);
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
    void triggerPoll() {
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
            if (!"pending".equals(msg.status())) {
                // get_inbox returns all messages regardless of status. Skip anything
                // not in "pending" — calling markDelivered on an archived message
                // overwrites the status backward (archived → delivered), which causes
                // the redelivery loop macbook-air reported on 2026-04-30.
                log.info("Skipping non-pending message: status=" + msg.status()
                    + " message_id=" + msg.messageId() + " thread_id=" + msg.threadId());
                if ("all".equals(msg.toNode())) {
                    brain.updateBroadcastWatermark(config.nodeName, msg.messageId());
                }
                continue;
            }
            if (isSelfBroadcast(msg, config.nodeName)) {
                // Broadcast originated from this node — we already wrote the
                // content; don't re-process our own output as inbound work.
                // The broadcast row is shared across all peers, so DO NOT
                // archive it (that would hide it from other peers). Just
                // advance our watermark so get_inbox stops returning it.
                log.info("Skipping self-broadcast: message_id=" + msg.messageId()
                    + " thread_id=" + msg.threadId());
                brain.updateBroadcastWatermark(config.nodeName, msg.messageId());
                continue;
            }
            String kind = RelayHandler.extractKind(msg.content());
            if (!"action".equals(kind)) {
                // ack / info messages don't need processor invocation — they're
                // non-actionable signals. Archive immediately so we don't spawn
                // a Claude CLI session just to "reply" to an acknowledgment.
                // This is what broke the poller with the 11h zombie: linuxserver
                // was running claude -p against an ack message whose content
                // was literally "Roger that. Good rollout."
                log.info("Skipping non-action message: kind=" + kind
                    + " message_id=" + msg.messageId() + " thread_id=" + msg.threadId()
                    + " from=" + msg.fromNode());
                brain.markArchived(msg.messageId());
                if ("all".equals(msg.toNode())) {
                    brain.updateBroadcastWatermark(config.nodeName, msg.messageId());
                }
                continue;
            }
            if (isRateLimited(msg.fromNode())) {
                log.warning("Rate limited sender=" + msg.fromNode()
                    + " (>" + RATE_LIMIT_MAX + " msgs/" + RATE_LIMIT_WINDOW.toMinutes() + "m)"
                    + " — archiving thread_id=" + msg.threadId() + " without processing");
                brain.markArchived(msg.messageId());
                continue;
            }
            dispatch(msg);
        }
    }

    /**
     * Hand a message off to a background virtual thread for processing, then
     * return immediately so the poll loop is never blocked by a slow task.
     *
     * The in-flight set is the dedup guard: if this message id is already being
     * processed (e.g. an overlapping poll saw it before markDelivered landed),
     * skip the duplicate dispatch. Same-thread ordering and serialization are
     * still enforced inside processWithThreadLock via the per-thread lock; the
     * global concurrency cap is enforced there via the processing semaphore.
     */
    private void dispatch(OpenBrainStore.PendingMessage msg) {
        if (!inFlight.add(msg.messageId())) {
            log.fine("Already in-flight — skipping duplicate dispatch message_id="
                + msg.messageId() + " thread_id=" + msg.threadId());
            return;
        }
        Thread.ofVirtual().name("msg-proc-" + msg.threadId()).start(() -> {
            try {
                processWithThreadLock(msg);
            } catch (Throwable t) {
                // processWithThreadLock handles its own exceptions; this is a
                // last-resort guard so a bug can never leak a stuck in-flight entry.
                log.warning("Unexpected error processing message_id=" + msg.messageId()
                    + " thread_id=" + msg.threadId() + ": " + t);
            } finally {
                inFlight.remove(msg.messageId());
            }
        });
    }

    /**
     * True when a broadcast (to_node="all") originated from this node.
     * Package-private for testing.
     */
    static boolean isSelfBroadcast(OpenBrainStore.PendingMessage msg, String nodeName) {
        return "all".equals(msg.toNode()) && nodeName.equals(msg.fromNode());
    }

    /**
     * Check if a sender has exceeded the rate limit (sliding window).
     * Returns true if the sender should be throttled.
     */
    private boolean isRateLimited(String sender) {
        Deque<Instant> timestamps = senderTimestamps.computeIfAbsent(sender, k -> new ArrayDeque<>());
        Instant cutoff = Instant.now().minus(RATE_LIMIT_WINDOW);

        synchronized (timestamps) {
            // Evict timestamps outside the window
            while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(cutoff)) {
                timestamps.pollFirst();
            }
            return timestamps.size() >= RATE_LIMIT_MAX;
        }
    }

    /** Record that a message from this sender was processed. */
    private void recordProcessed(String sender) {
        Deque<Instant> timestamps = senderTimestamps.computeIfAbsent(sender, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            timestamps.addLast(Instant.now());
        }
    }

    /**
     * Acquire the per-thread lock before processing, then release it.
     * Messages with different thread_ids run concurrently.
     * Messages with the same thread_id are serialized.
     */
    private void processWithThreadLock(OpenBrainStore.PendingMessage msg) {
        long threadId = msg.threadId();
        // Fair lock: when several messages of the same thread contend, they
        // acquire in arrival order, preserving per-thread message ordering even
        // though each runs on its own virtual thread.
        ReentrantLock lock = threadLocks.computeIfAbsent(threadId, id -> new ReentrantLock(true));
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
            // Claim the message before processing to prevent duplicate delivery.
            // If OpenBrain is unavailable here, skip and retry next poll (still "active").
            // Once claimed, subsequent polls won't see this message even if archiving later fails.
            // Mark delivered (pending → delivered) before processing.
            // This is the atomic "claim" step — prevents duplicate delivery if
            // the poll runs again before we finish.
            //
            // NOTE: unlike the old claimMessage/resetMessage pair, the messages
            // table has no "reset to pending" operation. If processing fails after
            // markDelivered(), the message stays in "delivered" state and will NOT
            // be retried by the normal poll path. Manual recovery would be needed.
            if (!brain.markDelivered(msg.messageId())) {
                log.warning("Could not mark message delivered thread_id=" + threadId
                    + " messageId=" + msg.messageId() + " — skipping, will retry next poll");
                return;
            }

            try {
                // Bound concurrent claude subprocesses across all threads.
                // Acquired only around the expensive processor call, not the
                // cheap OpenBrain bookkeeping, so permits free up promptly.
                processingSlots.acquire();
                try {
                    processor.process(msg);
                } finally {
                    processingSlots.release();
                }
                brain.markArchived(msg.messageId());
                if ("all".equals(msg.toNode())) {
                    brain.updateBroadcastWatermark(config.nodeName, msg.messageId());
                }
                recordProcessed(msg.fromNode());
                int processed = totalProcessed.incrementAndGet();
                log.info("Processed message thread_id=" + threadId
                    + " from=" + msg.fromNode()
                    + " total_processed=" + processed);
            } catch (Exception e) {
                log.warning("Failed to process message thread_id=" + threadId
                    + " messageId=" + msg.messageId() + ": " + e.getMessage());
                // Attempt to reset to pending so the message can be retried.
                // resetDelivered() is a stub — always returns false until macmini
                // adds mark_pending to the OpenBrain MCP API (see OpenBrainStore).
                if (!brain.resetDelivered(msg.messageId())) {
                    // Reset unavailable — dead-letter: archive to clear "delivered"
                    // limbo, then log to OpenBrain for auditability.
                    log.warning("Reset unavailable — archiving as dead-letter:"
                        + " thread_id=" + threadId + " messageId=" + msg.messageId());
                    brain.markArchived(msg.messageId());
                    brain.storeDeadLetter(msg, e.getMessage());
                }
                // If reset succeeded, message returns to pending and will be
                // retried on the next poll cycle.
            }
        } finally {
            lock.unlock();
            // Remove lock if no other thread is waiting on it
            threadLocks.remove(threadId, lock);
        }
    }

    public void stop() { running = false; }

    public Instant getLastPollTime()   { return lastPollTime; }
    public int     getTotalProcessed() { return totalProcessed.get(); }

    /** Number of messages currently dispatched but not yet finished processing. */
    int inFlightCount() { return inFlight.size(); }

    /**
     * Block until all dispatched messages have finished processing, or the
     * timeout elapses. Processing now runs on background virtual threads
     * (messenger#27), so callers that need to observe the terminal state —
     * tests, graceful shutdown — use this to await quiescence.
     *
     * @return true if processing drained within the timeout, false otherwise
     */
    boolean awaitProcessingComplete(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (!inFlight.isEmpty()) {
            if (System.nanoTime() >= deadline) return false;
            Thread.sleep(10);
        }
        return true;
    }
}
