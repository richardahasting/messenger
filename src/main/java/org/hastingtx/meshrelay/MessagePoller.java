package org.hastingtx.meshrelay;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
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
    static final Duration POLL_INTERVAL = Duration.ofMinutes(10);

    /**
     * Backstop ceiling on processor invocations per thread (see
     * docs/protocol-v1.2.md § "Backstop: max-turns ceiling").
     *
     * Defense-in-depth: if a future {@code kind} is introduced without analysing
     * its loop properties, this ceiling caps the runaway. {@code kind=progress}
     * is excluded from the count — a 1-hour task at 30s beats produces ~120
     * progress messages, which would otherwise trip the ceiling.
     *
     * Stub for v1.2.0 (2/9). Enforcement is added in issue #14.
     */
    static final int MAX_TURNS_PER_THREAD = 20;

    private final PeerConfig       config;
    private final OpenBrainStore   brain;
    private final MessageProcessor processor;
    private final RelaySender      relaySender;

    private volatile boolean running  = true;
    private Instant lastPollTime      = Instant.EPOCH;
    private int     totalProcessed    = 0;

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

    // ── Per-sender rate limiting ──────────────────────────────────────────
    // Prevents chatty agents (e.g. Gemma hallucinating tasks) from flooding
    // the processor. 3 messages per 10 minutes per sender.

    private static final int    RATE_LIMIT_MAX     = 3;
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(10);

    /** sender → timestamps of recently processed messages (sliding window). */
    private final ConcurrentHashMap<String, Deque<Instant>> senderTimestamps = new ConcurrentHashMap<>();

    public MessagePoller(PeerConfig config, OpenBrainStore brain, MessageProcessor processor) {
        this(config, brain, processor, RelaySender.NOOP);
    }

    public MessagePoller(PeerConfig config, OpenBrainStore brain, MessageProcessor processor,
                         RelaySender relaySender) {
        this.config      = config;
        this.brain       = brain;
        this.processor   = processor;
        this.relaySender = relaySender != null ? relaySender : RelaySender.NOOP;
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
                // Non-action kinds don't run the processor — they're either
                // protocol signals (ack, reply, progress, ping) or one-way
                // notifications (info). Archive immediately so we don't spawn
                // a Claude CLI session just to "reply" to an acknowledgment.
                // This is what broke the poller with the 11h zombie: linuxserver
                // was running claude -p against an ack message whose content
                // was literally "Roger that. Good rollout."
                //
                // ping (issue #14): daemon auto-responds with kind=reply
                // payload="pong" before archiving. reply→waiter delivery
                // (dedup cache notification) lands in issue #17.
                if ("ping".equals(kind)) {
                    handlePing(msg);
                } else if (isKnownNonActionKind(kind)) {
                    log.info("Skipping non-action message: kind=" + kind
                        + " message_id=" + msg.messageId() + " thread_id=" + msg.threadId()
                        + " from=" + msg.fromNode());
                } else {
                    log.warning("Unknown kind — archiving defensively: kind=" + kind
                        + " message_id=" + msg.messageId() + " thread_id=" + msg.threadId()
                        + " from=" + msg.fromNode());
                }
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
            processWithThreadLock(msg);
        }
    }

    /**
     * True when a broadcast (to_node="all") originated from this node.
     * Package-private for testing.
     */
    static boolean isSelfBroadcast(OpenBrainStore.PendingMessage msg, String nodeName) {
        return "all".equals(msg.toNode()) && nodeName.equals(msg.fromNode());
    }

    /**
     * True for v1.2 kinds that the poller recognises but does not run through
     * the processor: {@code reply}, {@code ack}, {@code info}, {@code progress},
     * {@code ping}. Any other non-action value is treated as unknown and logged
     * as a warning (defensive archive — defends against protocol-version drift).
     * Package-private for testing.
     */
    static boolean isKnownNonActionKind(String kind) {
        return switch (kind) {
            case "reply", "ack", "info", "progress", "ping" -> true;
            default -> false;
        };
    }

    /**
     * Daemon-handled response to {@code kind=ping} — emit
     * {@code kind=reply payload="pong" reply_policy=NO_REPLY} via the same
     * /relay channel all other outbound traffic uses, then let the caller
     * archive the inbound ping.
     *
     * <p>Honours the spec's "with {@code ack_policy=REQ_ACK}" guard
     * (docs/protocol-v1.2.md § "Receiver behavior" step 3): if the sender
     * explicitly set {@code ack=NO_ACK}, no pong is emitted. Default is
     * REQ_ACK, so the typical inbound ping gets a pong back.
     *
     * <p>The reply carries {@code in_reply_to=<inbound seq_id>} so the caller
     * can correlate the pong to its outbound ping. If the inbound header has
     * no {@code seq=} (pre-v1.2 sender, or malformed), a synthetic
     * {@code <peer>:<thread>:0} is used so the {@code /relay} 400-on-missing
     * guard does not fire.
     */
    private void handlePing(OpenBrainStore.PendingMessage msg) {
        String content    = msg.content();
        String ackPolicy  = RelayHandler.extractHeaderField(content, "ack", "REQ_ACK");
        if (!"REQ_ACK".equals(ackPolicy)) {
            log.info("Skipping pong for ping with ack=" + ackPolicy
                + " from=" + msg.fromNode() + " thread_id=" + msg.threadId());
            return;
        }
        String seq = RelayHandler.extractHeaderField(content, "seq", null);
        if (seq == null || seq.isBlank()) {
            // Pre-v1.2 sender, or stripped header — synthesise so the reply's
            // mandatory in_reply_to is non-empty.
            seq = msg.fromNode() + ":" + msg.threadId() + ":0";
        }

        boolean sent = relaySender.send(
            /*to=*/         msg.fromNode(),
            /*from=*/       config.nodeName,
            /*content=*/    "pong",
            /*kind=*/       "reply",
            /*inReplyTo=*/  seq,
            /*replyPolicy=*/"NO_REPLY",
            /*threadId=*/   msg.threadId());

        if (sent) {
            log.info("Auto-replied to ping from=" + msg.fromNode() + " seq=" + seq);
        } else {
            log.warning("Auto-pong send failed — peer will see no reply: from="
                + msg.fromNode() + " seq=" + seq);
        }
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
                processor.process(msg);
                brain.markArchived(msg.messageId());
                if ("all".equals(msg.toNode())) {
                    brain.updateBroadcastWatermark(config.nodeName, msg.messageId());
                }
                recordProcessed(msg.fromNode());
                totalProcessed++;
                log.info("Processed message thread_id=" + threadId
                    + " from=" + msg.fromNode()
                    + " total_processed=" + totalProcessed);
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
    public int     getTotalProcessed() { return totalProcessed; }
}
