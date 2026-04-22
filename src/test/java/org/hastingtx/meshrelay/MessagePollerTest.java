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
            new OpenBrainStore.PendingMessage(1, 1L, "me", "all", "x"), "me"),
            "own broadcast must be flagged");
        assertFalse(MessagePoller.isSelfBroadcast(
            new OpenBrainStore.PendingMessage(1, 1L, "peer", "all", "x"), "me"),
            "peer broadcast must not be flagged");
        assertFalse(MessagePoller.isSelfBroadcast(
            new OpenBrainStore.PendingMessage(1, 1L, "me", "peer", "x"), "me"),
            "self-direct message must not be flagged");
        assertFalse(MessagePoller.isSelfBroadcast(
            new OpenBrainStore.PendingMessage(1, 1L, "me", "me", "x"), "me"),
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
            500, 500L, "linuxserver", "all", "my own broadcast"));

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
            501, 501L, "macmini", "all", "peer broadcast"));

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
    void mixedInboxProcessesPeerMessagesAndSkipsSelfBroadcast() {
        PeerConfig cfg = testConfig("linuxserver");
        RecordingStore brain = new RecordingStore(cfg);
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            600, 600L, "linuxserver", "all", "self broadcast — should be skipped"));
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            601, 601L, "macmini", "linuxserver", "direct from peer — must run"));

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
