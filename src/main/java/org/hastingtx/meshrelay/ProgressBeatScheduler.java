package org.hastingtx.meshrelay;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Daemon-driven progress heartbeat for v1.2 {@code kind=progress} (issue #17).
 *
 * <p>The processor (Claude CLI, Gemma, shell scripts) does NOT need to know
 * about the timer. It writes to a known log file ({@code MESSENGER_PROGRESS_LOG}
 * env var). This scheduler tails that file on a fixed schedule and emits a
 * {@code kind=progress} relay back to the original sender. See
 * docs/protocol-v1.2.md § "Progress mechanism — daemon-driven heartbeat with
 * log tailing".
 *
 * <p>Single-threaded {@link ScheduledExecutorService} (daemon thread). Tasks
 * are tracked by {@code (threadId, seqId)} so the same scheduler can drive
 * multiple in-flight conversations.
 */
public class ProgressBeatScheduler {

    private static final Logger log = Logger.getLogger(ProgressBeatScheduler.class.getName());

    /** Spec § "Constraints": clamp the sender's update_interval_seconds to [30, 120]. */
    static final int MIN_INTERVAL_SECONDS = 30;
    static final int MAX_INTERVAL_SECONDS = 120;

    /** Spec § "Receiver-side flow": tail = last 10 lines, capped at 4096 bytes. */
    static final int TAIL_MAX_LINES = 10;
    static final int TAIL_MAX_BYTES = 4096;

    /** Spec § "Receiver-side flow": empty log → "(no log activity)". */
    static final String EMPTY_TAIL_PAYLOAD = "(no log activity)";

    private final ScheduledExecutorService executor;
    private final RelaySender             relaySender;
    private final String                  nodeName;
    private final ConcurrentMap<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    public ProgressBeatScheduler(RelaySender relaySender, String nodeName) {
        this(relaySender, nodeName, defaultExecutor());
    }

    ProgressBeatScheduler(RelaySender relaySender, String nodeName,
                          ScheduledExecutorService executor) {
        this.relaySender = relaySender != null ? relaySender : RelaySender.NOOP;
        this.nodeName    = nodeName;
        this.executor    = executor;
    }

    private static ScheduledExecutorService defaultExecutor() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "progress-beat-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Schedule a recurring progress beat for the given thread/seq. Interval is
     * clamped to {@code [30, 120]} seconds per spec; callers pass the raw
     * sender-requested value and trust the scheduler to enforce sanity.
     *
     * @param threadId        conversation thread (passes through to the relay)
     * @param seqId           the action's seq_id — emitted as in_reply_to so
     *                        the caller correlates beats to the request
     * @param originalSender  the node that sent the action; progress beats go
     *                        back to this peer
     * @param intervalSeconds sender's requested cadence; clamped to [30, 120]
     * @param logPath         file the processor writes to; tailed on each tick
     */
    public void start(long threadId, String seqId, String originalSender,
                       int intervalSeconds, Path logPath) {
        int clamped = clampInterval(intervalSeconds);
        scheduleTask(threadId, seqId, originalSender, logPath, clamped, TimeUnit.SECONDS);
    }

    /**
     * Test-only: schedule with a non-clamped, sub-second cadence so unit tests
     * can verify cadence behavior without sleeping for 30+ seconds. Production
     * callers go through {@link #start}, which enforces the [30, 120] s clamp.
     */
    void startUnclampedForTest(long threadId, String seqId, String originalSender,
                                long intervalMillis, Path logPath) {
        scheduleTask(threadId, seqId, originalSender, logPath,
            intervalMillis, TimeUnit.MILLISECONDS);
    }

    private void scheduleTask(long threadId, String seqId, String originalSender,
                               Path logPath, long interval, TimeUnit unit) {
        String key = key(threadId, seqId);
        Runnable task = () -> {
            try {
                emit(threadId, seqId, originalSender, logPath);
            } catch (Throwable t) {
                // ScheduledExecutorService cancels future runs if the task
                // throws — swallow defensively so a single-tick error doesn't
                // silently kill the heartbeat for the rest of the conversation.
                log.log(Level.WARNING,
                    "Progress beat tick failed for thread_id=" + threadId
                        + " seq=" + seqId, t);
            }
        };
        ScheduledFuture<?> future = executor.scheduleAtFixedRate(
            task, interval, interval, unit);
        ScheduledFuture<?> previous = tasks.put(key, future);
        if (previous != null) previous.cancel(false);
    }

    /** Cancel the recurring beat for the given thread/seq. Safe to call multiple times. */
    public void stop(long threadId, String seqId) {
        ScheduledFuture<?> future = tasks.remove(key(threadId, seqId));
        if (future != null) future.cancel(false);
    }

    /** Stop all scheduled beats and shut down the executor. */
    public void shutdown() {
        for (ScheduledFuture<?> f : tasks.values()) f.cancel(false);
        tasks.clear();
        executor.shutdownNow();
    }

    // ── Helpers (package-private for testing) ────────────────────────────

    static int clampInterval(int interval) {
        if (interval < MIN_INTERVAL_SECONDS) return MIN_INTERVAL_SECONDS;
        if (interval > MAX_INTERVAL_SECONDS) return MAX_INTERVAL_SECONDS;
        return interval;
    }

    private static String key(long threadId, String seqId) {
        return threadId + ":" + (seqId == null ? "" : seqId);
    }

    private void emit(long threadId, String seqId, String originalSender, Path logPath) {
        String tail = readTail(logPath, TAIL_MAX_LINES, TAIL_MAX_BYTES);
        String payload;
        if (tail == null || tail.isBlank()) {
            payload = EMPTY_TAIL_PAYLOAD;
        } else {
            payload = SecretsSweeper.redact(tail);
        }
        relaySender.send(originalSender, nodeName, payload,
            "progress", seqId, "NO_REPLY", threadId);
    }

    /**
     * Read the last {@code maxLines} lines of {@code logPath}, capped at
     * {@code maxBytes} bytes from the end of the file. Returns "" when the
     * file is missing, empty, or unreadable — caller treats that as the
     * "(no log activity)" case.
     */
    static String readTail(Path logPath, int maxLines, int maxBytes) {
        if (logPath == null) return "";
        try {
            if (!Files.exists(logPath)) return "";
            long size = Files.size(logPath);
            if (size == 0) return "";

            int readLen = (int) Math.min(maxBytes, size);
            long startPos = size - readLen;
            byte[] buf = new byte[readLen];

            try (FileChannel ch = FileChannel.open(logPath, StandardOpenOption.READ)) {
                ch.position(startPos);
                ByteBuffer bb = ByteBuffer.wrap(buf);
                while (bb.hasRemaining()) {
                    int r = ch.read(bb);
                    if (r < 0) break;
                }
            }

            String s = new String(buf, StandardCharsets.UTF_8);
            List<String> lines = new ArrayList<>(Arrays.asList(s.split("\\R", -1)));
            // Drop trailing empty line caused by a terminal newline.
            while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
                lines.remove(lines.size() - 1);
            }
            if (lines.isEmpty()) return "";

            int from = Math.max(0, lines.size() - maxLines);
            return String.join("\n", lines.subList(from, lines.size()));
        } catch (IOException e) {
            log.warning("Failed to tail progress log " + logPath + ": " + e.getMessage());
            return "";
        }
    }

    /** Test-only: returns the number of currently scheduled beats. */
    int activeTaskCount() {
        return tasks.size();
    }
}
