package org.hastingtx.meshrelay;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.logging.Logger;

/**
 * Background polling loop — queries OpenBrain every 10 minutes for pending
 * messages addressed to this node and processes them.
 *
 * This is the reliability safety net. Wake-up pings are the fast path;
 * this poll is the guaranteed catch-up path. A message missed due to a
 * failed wake-up (node down, network blip) will be found here within 10 minutes.
 *
 * Processing:
 *   1. Search OpenBrain for thoughts: project=mesh-messages, status=active,
 *      tags=[to:thisNode] or [to:all]
 *   2. For each pending message: log it and hand off to the MessageProcessor
 *   3. Mark each processed message archived in OpenBrain
 *
 * The MessageProcessor is the pluggable piece — today it logs and acknowledges;
 * later it will invoke a Claude agent or Gemma4 depending on message complexity.
 */
public class MessagePoller implements Runnable {

    private static final Logger   log           = Logger.getLogger(MessagePoller.class.getName());
    private static final Duration POLL_INTERVAL = Duration.ofMinutes(10);

    private final PeerConfig       config;
    private final OpenBrainStore   brain;
    private final MessageProcessor processor;

    private volatile boolean running = true;
    private Instant lastPollTime     = Instant.EPOCH;
    private int     totalProcessed   = 0;

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
        poll();

        while (running) {
            try {
                Thread.sleep(POLL_INTERVAL);
                poll();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Execute one poll cycle: fetch pending messages, process each, mark archived.
     * Errors in individual messages are logged and skipped — a bad message
     * should never stop the poller from processing the rest.
     */
    public void poll() {
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
            try {
                processor.process(msg);
                brain.markArchived(msg.thoughtId());
                totalProcessed++;
                log.info("Processed message thread_id=" + msg.thoughtId()
                    + " from=" + msg.fromNode()
                    + " total_processed=" + totalProcessed);
            } catch (Exception e) {
                // Log and continue — do NOT mark archived so it will be retried next poll
                log.warning("Failed to process message thread_id=" + msg.thoughtId()
                    + ": " + e.getMessage() + " — will retry next poll");
            }
        }
    }

    /** Trigger an immediate poll — called when a wake-up ping arrives. */
    public void wake() {
        log.info("Wake-up received — triggering immediate poll");
        Thread.ofVirtual().start(this::poll);
    }

    public void stop() { running = false; }

    public Instant getLastPollTime()  { return lastPollTime; }
    public int     getTotalProcessed() { return totalProcessed; }
}
