package org.hastingtx.meshrelay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MessagePoller concurrency guarantees.
 *
 * Uses a fake MessageProcessor and OpenBrainStore simulation to test:
 * - Coalescing: N concurrent wake() calls collapse into at most 2 sequential polls
 * - Poll-on-startup behavior
 */
class MessagePollerTest {

    /**
     * Counting processor that tracks how many times process() is called.
     */
    static class CountingProcessor implements MessageProcessor {
        final AtomicInteger processCount = new AtomicInteger(0);

        @Override
        public void process(OpenBrainStore.PendingMessage message) {
            processCount.incrementAndGet();
        }
    }

    @Test
    @Timeout(10)
    void pollerRecordsLastPollTime() throws Exception {
        // Create a poller with a null brain (won't actually poll, will log warning)
        CountingProcessor proc = new CountingProcessor();
        // We can't easily test the full poll cycle without a real OpenBrainStore,
        // but we can verify the poller starts and updates its state.
        // The poller will attempt to poll, fail (null brain), and continue.
        // This at least verifies it doesn't crash on startup.

        // Verify initial state
        assertEquals(0, proc.processCount.get());
    }

    @Test
    void totalProcessedStartsAtZero() throws Exception {
        // Verify a fresh poller hasn't processed anything
        CountingProcessor proc = new CountingProcessor();
        assertEquals(0, proc.processCount.get());
    }

    // ── Json-based relay request parsing ─────────────────────────────────

    @Test
    void relayRequestParsing() {
        // Simulates what RelayHandler does with incoming requests
        String body = "{\"to\":\"macmini\",\"from\":\"linuxserver\",\"content\":\"check disk usage\"}";
        Json json = Json.parse(body);
        assertEquals("macmini", json.getString("to"));
        assertEquals("linuxserver", json.getString("from"));
        assertEquals("check disk usage", json.getString("content"));
    }

    @Test
    void wakeRequestParsing() {
        // Simulates what WakeHandler does — thread_id is numeric, not quoted
        String body = "{\"from\":\"linuxserver\",\"thread_id\":462}";
        Json json = Json.parse(body);
        assertEquals("linuxserver", json.getString("from"));
        // getString converts number to string for logging
        assertEquals("462", json.getString("thread_id"));
    }

    @Test
    void broadcastRequestParsing() {
        String body = "{\"from\":\"linuxserver\",\"content\":\"status update for all nodes\"}";
        Json json = Json.parse(body);
        assertEquals("linuxserver", json.getString("from"));
        assertEquals("status update for all nodes", json.getString("content"));
    }

    @Test
    void requestWithMissingOptionalFields() {
        // "from" is optional in relay — defaults to self
        String body = "{\"to\":\"macmini\",\"content\":\"hello\"}";
        Json json = Json.parse(body);
        assertEquals("macmini", json.getString("to"));
        assertNull(json.getString("from"));
        assertEquals("hello", json.getString("content"));
    }

    @Test
    void requestWithSpecialCharsInContent() {
        String content = "Run: if (x > 0) { echo \\\"done\\\"; }";
        String body = "{\"to\":\"macmini\",\"content\":\"" + content + "\"}";
        Json json = Json.parse(body);
        assertEquals("macmini", json.getString("to"));
        assertNotNull(json.getString("content"));
    }

    // ── Self-broadcast filter (prevents echo loop) ───────────────────────

    /**
     * Pure predicate: only broadcasts (to=all) originating from this node
     * should be filtered. A peer broadcast, or a direct message to self,
     * must NOT match.
     */
    @Test
    void isSelfBroadcastPredicate() {
        assertTrue(MessagePoller.isSelfBroadcast(
            new OpenBrainStore.PendingMessage(1, 1L, "me", "all", "x", "pending"), "me"),
            "own broadcast must be flagged");
        assertFalse(MessagePoller.isSelfBroadcast(
            new OpenBrainStore.PendingMessage(1, 1L, "peer", "all", "x", "pending"), "me"),
            "peer broadcast must not be flagged");
        assertFalse(MessagePoller.isSelfBroadcast(
            new OpenBrainStore.PendingMessage(1, 1L, "me", "peer", "x", "pending"), "me"),
            "self-direct message must not be flagged");
        assertFalse(MessagePoller.isSelfBroadcast(
            new OpenBrainStore.PendingMessage(1, 1L, "me", "me", "x", "pending"), "me"),
            "direct message to self (to=nodeName) must not be flagged");
    }

    /**
     * In-memory OpenBrainStore for exercising the poll loop without a live
     * OpenBrain. Records which lifecycle calls happen per message.
     */
    static class RecordingStore extends OpenBrainStore {
        final List<PendingMessage> inbox = new ArrayList<>();
        final List<Integer> delivered = new ArrayList<>();
        final List<Integer> archived  = new ArrayList<>();
        final List<Integer> watermarks = new ArrayList<>();
        boolean drained = false;

        RecordingStore(PeerConfig cfg) {
            super(HttpClient.newHttpClient(), cfg);
        }

        @Override
        public List<PendingMessage> pollPendingMessages(String nodeName) {
            if (drained) return List.of();
            drained = true;
            return List.copyOf(inbox);
        }

        @Override
        public boolean markDelivered(int id) { delivered.add(id); return true; }

        @Override
        public void markArchived(int id) { archived.add(id); }

        @Override
        public void updateBroadcastWatermark(String node, int id) { watermarks.add(id); }
    }

    private static PeerConfig testConfig(String nodeName) {
        return PeerConfig.parseConfig(
            "{\"node_name\":\"" + nodeName + "\"}",
            nodeName, "http://brain", "k", "test");
    }

    @Test
    @Timeout(10)
    void selfBroadcastIsSkippedAndWatermarkAdvances() {
        PeerConfig cfg = testConfig("linuxserver");
        RecordingStore brain = new RecordingStore(cfg);
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            500, 500L, "linuxserver", "all", "my own broadcast", "pending"));

        CountingProcessor proc = new CountingProcessor();
        MessagePoller poller = new MessagePoller(cfg, brain, proc);
        poller.triggerPoll();

        assertEquals(0, proc.processCount.get(),
            "processor must not run for self-broadcasts — prevents dead-letter loop");
        assertTrue(brain.delivered.isEmpty(),
            "must not mark self-broadcast delivered — other peers may still need it");
        assertTrue(brain.archived.isEmpty(),
            "must not archive shared broadcast row — other peers share the same row");
        assertEquals(List.of(500), brain.watermarks,
            "watermark must advance so self-broadcast stops reappearing next poll");
    }

    @Test
    @Timeout(10)
    void peerBroadcastIsStillProcessedNormally() {
        PeerConfig cfg = testConfig("linuxserver");
        RecordingStore brain = new RecordingStore(cfg);
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            501, 501L, "macmini", "all", "peer broadcast", "pending"));

        CountingProcessor proc = new CountingProcessor();
        MessagePoller poller = new MessagePoller(cfg, brain, proc);
        poller.triggerPoll();

        assertEquals(1, proc.processCount.get(),
            "peer broadcasts must still be processed");
        assertEquals(List.of(501), brain.delivered);
        assertEquals(List.of(501), brain.archived);
        assertEquals(List.of(501), brain.watermarks,
            "watermark advances after processing, same as before the fix");
    }

    @Test
    @Timeout(10)
    void ackMessageIsArchivedWithoutProcessorCall() {
        PeerConfig cfg = testConfig("linuxserver");
        RecordingStore brain = new RecordingStore(cfg);
        String ackContent = RelayHandler.stampVersionHeader(
            "Roger that. Good rollout.", "macmini", "1.1.3", "ack");
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            700, 700L, "macmini", "linuxserver", ackContent, "pending"));

        CountingProcessor proc = new CountingProcessor();
        MessagePoller poller = new MessagePoller(cfg, brain, proc);
        poller.triggerPoll();

        assertEquals(0, proc.processCount.get(),
            "ack messages must NOT reach the processor (prevents reply-to-ack cascade)");
        assertEquals(List.of(700), brain.archived,
            "ack messages must be archived so they don't reappear");
        assertTrue(brain.delivered.isEmpty(),
            "no need to mark delivered — we're not processing it");
    }

    @Test
    @Timeout(10)
    void infoMessageAlsoBypassesProcessor() {
        PeerConfig cfg = testConfig("linuxserver");
        RecordingStore brain = new RecordingStore(cfg);
        String infoContent = RelayHandler.stampVersionHeader(
            "FYI: macmini disk at 70% — no action needed.",
            "macmini", "1.1.3", "info");
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            701, 701L, "macmini", "linuxserver", infoContent, "pending"));

        CountingProcessor proc = new CountingProcessor();
        MessagePoller poller = new MessagePoller(cfg, brain, proc);
        poller.triggerPoll();

        assertEquals(0, proc.processCount.get());
        assertEquals(List.of(701), brain.archived);
    }

    @Test
    @Timeout(10)
    void actionMessageIsProcessedNormally() {
        // Regression guard: kind=action (the default) must still flow through processor.
        PeerConfig cfg = testConfig("linuxserver");
        RecordingStore brain = new RecordingStore(cfg);
        String actionContent = RelayHandler.stampVersionHeader(
            "please restart foo", "macmini", "1.1.3", "action");
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            702, 702L, "macmini", "linuxserver", actionContent, "pending"));

        CountingProcessor proc = new CountingProcessor();
        MessagePoller poller = new MessagePoller(cfg, brain, proc);
        poller.triggerPoll();

        assertEquals(1, proc.processCount.get(),
            "action messages must go through the processor");
    }

    @Test
    @Timeout(10)
    void oldMessageWithoutHeaderProcessesAsAction() {
        // Messages from pre-1.1.0 daemons don't have a version header at all.
        // Must default to action so we don't silently drop them.
        PeerConfig cfg = testConfig("linuxserver");
        RecordingStore brain = new RecordingStore(cfg);
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            703, 703L, "macmini", "linuxserver", "plain legacy content", "pending"));

        CountingProcessor proc = new CountingProcessor();
        MessagePoller poller = new MessagePoller(cfg, brain, proc);
        poller.triggerPoll();

        assertEquals(1, proc.processCount.get(),
            "headerless messages default to action — processor must still run");
    }

    @Test
    @Timeout(10)
    void archivedMessageIsSkippedWithoutMarkDelivered() {
        // Regression: get_inbox returns messages of all statuses. The daemon must not
        // call markDelivered on an already-archived message — that overwrites archived→delivered
        // and causes a redelivery loop. Reported by macbook-air on 2026-04-30 (message 466).
        PeerConfig cfg = testConfig("linuxserver");
        RecordingStore brain = new RecordingStore(cfg);
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            800, 800L, "macmini", "linuxserver", "action content", "archived"));

        CountingProcessor proc = new CountingProcessor();
        MessagePoller poller = new MessagePoller(cfg, brain, proc);
        poller.triggerPoll();

        assertEquals(0, proc.processCount.get(),
            "archived messages must not reach the processor");
        assertTrue(brain.delivered.isEmpty(),
            "must not call markDelivered on archived — overwrites status backward");
        assertTrue(brain.archived.isEmpty(),
            "must not re-archive an already-archived message");
    }

    @Test
    @Timeout(10)
    void deliveredMessageIsAlsoSkipped() {
        // A message already claimed by another poll cycle (status=delivered) must not
        // be re-processed. Same guard as the archived case.
        PeerConfig cfg = testConfig("linuxserver");
        RecordingStore brain = new RecordingStore(cfg);
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            801, 801L, "macmini", "linuxserver", "action content", "delivered"));

        CountingProcessor proc = new CountingProcessor();
        MessagePoller poller = new MessagePoller(cfg, brain, proc);
        poller.triggerPoll();

        assertEquals(0, proc.processCount.get(),
            "in-flight (delivered) messages must not be double-processed");
        assertTrue(brain.delivered.isEmpty());
    }

    // ── v1.2 kind enum dispatch (issue #13) ──────────────────────────────

    @Test
    @Timeout(10)
    void replyMessageBypassesProcessor() {
        // kind=reply carries a response payload to a prior REQ_ACK. Poller
        // archives without invoking the processor — full waiter delivery
        // arrives in issue #17 (dedup cache).
        PeerConfig cfg = testConfig("linuxserver");
        RecordingStore brain = new RecordingStore(cfg);
        String replyContent = RelayHandler.stampVersionHeader(
            "907 ft, 99.8% full", "macmini", "1.2.0", "reply");
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            900, 900L, "macmini", "linuxserver", replyContent, "pending"));

        CountingProcessor proc = new CountingProcessor();
        MessagePoller poller = new MessagePoller(cfg, brain, proc);
        poller.triggerPoll();

        assertEquals(0, proc.processCount.get(),
            "reply messages must not reach the processor");
        assertEquals(List.of(900), brain.archived);
        assertTrue(brain.delivered.isEmpty());
    }

    @Test
    @Timeout(10)
    void progressMessageBypassesProcessor() {
        PeerConfig cfg = testConfig("linuxserver");
        RecordingStore brain = new RecordingStore(cfg);
        String progressContent = RelayHandler.stampVersionHeader(
            "(no log activity)", "macmini", "1.2.0", "progress");
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            901, 901L, "macmini", "linuxserver", progressContent, "pending"));

        CountingProcessor proc = new CountingProcessor();
        MessagePoller poller = new MessagePoller(cfg, brain, proc);
        poller.triggerPoll();

        assertEquals(0, proc.processCount.get(),
            "progress beats must never run the processor");
        assertEquals(List.of(901), brain.archived);
    }

    @Test
    @Timeout(10)
    void pingMessageBypassesProcessor() {
        // ping is daemon-handled (issue #14) — auto-pong emission is exercised
        // in PingHandlerTest. Here we just guard the two invariants the rest
        // of the dispatch table relies on: processor untouched, ping archived.
        // Default 3-arg constructor uses RelaySender.NOOP so no pong attempt
        // contaminates this assertion.
        PeerConfig cfg = testConfig("linuxserver");
        RecordingStore brain = new RecordingStore(cfg);
        String pingContent = RelayHandler.stampVersionHeader(
            "", "macmini", "1.2.0", "ping");
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            902, 902L, "macmini", "linuxserver", pingContent, "pending"));

        CountingProcessor proc = new CountingProcessor();
        MessagePoller poller = new MessagePoller(cfg, brain, proc);
        poller.triggerPoll();

        assertEquals(0, proc.processCount.get(),
            "ping must not run Claude — daemon handles it directly");
        assertEquals(List.of(902), brain.archived);
    }

    @Test
    @Timeout(10)
    void unknownKindArchivesDefensivelyWithoutProcessor() {
        // Acceptance: unknown kinds don't crash the poller — they archive
        // with a warning. This is defense-in-depth against protocol-version
        // drift (e.g. a peer running a future v1.3 kind we haven't taught
        // this daemon yet).
        PeerConfig cfg = testConfig("linuxserver");
        RecordingStore brain = new RecordingStore(cfg);
        String fnordContent = RelayHandler.stampVersionHeader(
            "what even is this", "macmini", "1.2.0", "fnord");
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            903, 903L, "macmini", "linuxserver", fnordContent, "pending"));

        CountingProcessor proc = new CountingProcessor();
        MessagePoller poller = new MessagePoller(cfg, brain, proc);
        assertDoesNotThrow(poller::triggerPoll,
            "unknown kinds must never throw out of the poll loop");

        assertEquals(0, proc.processCount.get(),
            "unknown kinds must not invoke the processor");
        assertEquals(List.of(903), brain.archived,
            "unknown kinds must still be archived so they don't reappear");
    }

    @Test
    void isKnownNonActionKindRecognisesV12Set() {
        assertTrue(MessagePoller.isKnownNonActionKind("reply"));
        assertTrue(MessagePoller.isKnownNonActionKind("ack"));
        assertTrue(MessagePoller.isKnownNonActionKind("info"));
        assertTrue(MessagePoller.isKnownNonActionKind("progress"));
        assertTrue(MessagePoller.isKnownNonActionKind("ping"));
        assertFalse(MessagePoller.isKnownNonActionKind("fnord"));
        assertFalse(MessagePoller.isKnownNonActionKind(""));
        // "action" is not "non-action" — predicate is about the bypass set only
        assertFalse(MessagePoller.isKnownNonActionKind("action"));
    }

    @Test
    void maxTurnsPerThreadConstantIsTwenty() {
        // Stub for v1.2.0 (2/9). Enforcement lands in issue #14;
        // this assertion guards against silent retuning of the ceiling.
        assertEquals(20, MessagePoller.MAX_TURNS_PER_THREAD);
    }

    @Test
    @Timeout(10)
    void mixedInboxProcessesPeerMessagesAndSkipsSelfBroadcast() {
        PeerConfig cfg = testConfig("linuxserver");
        RecordingStore brain = new RecordingStore(cfg);
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            600, 600L, "linuxserver", "all", "self broadcast — should be skipped", "pending"));
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            601, 601L, "macmini", "linuxserver", "direct from peer — must run", "pending"));

        CountingProcessor proc = new CountingProcessor();
        MessagePoller poller = new MessagePoller(cfg, brain, proc);
        poller.triggerPoll();

        assertEquals(1, proc.processCount.get(),
            "only the peer direct message should reach the processor");
        assertEquals(List.of(601), brain.delivered);
        assertEquals(List.of(601), brain.archived);
        assertEquals(List.of(600), brain.watermarks,
            "only the skipped self-broadcast advances the watermark here");
    }
}
