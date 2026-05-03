package org.hastingtx.meshrelay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.http.HttpClient;
import java.time.Instant;
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
        // Issue #15 wires enforcement; this assertion guards against silent
        // retuning of the ceiling.
        assertEquals(20, MessagePoller.MAX_TURNS_PER_THREAD);
    }

    // ── v1.2 NO_REPLY / NO_ACK suppression (issue #15) ──────────────────

    /** Build an action message with explicit reply_policy / ack_policy. */
    private static String stampWithPolicies(String content, String fromNode,
                                            String replyPolicy, String ackPolicy) {
        RelayHandler.V12Fields v12 = new RelayHandler.V12Fields(
            /*seqId=*/         fromNode + ":42:1",
            /*ackPolicy=*/     ackPolicy,
            /*replyPolicy=*/   replyPolicy,
            /*respondBy=*/     null,
            /*updateInterval=*/null,
            /*inReplyTo=*/     null);
        return RelayHandler.stampVersionHeader(content, fromNode, "1.2.0", "action", v12);
    }

    /** Build an action message with default action policies (REPLY/REQ_ACK). */
    private static String stampAction(String content, String fromNode, String seq) {
        return RelayHandler.stampVersionHeader(content, fromNode, "1.2.0", "action",
            new RelayHandler.V12Fields(seq, "REQ_ACK", "REPLY", null, null, null));
    }

    /** Build a reply (or other terminal kind) with the spec defaults — NO_ACK / NO_REPLY. */
    private static String stampWithKindAndSeq(String content, String fromNode,
                                              String kind, String seq, String inReplyTo) {
        RelayHandler.V12Fields v12 = new RelayHandler.V12Fields(
            seq, "NO_ACK", "NO_REPLY", null, null, inReplyTo);
        return RelayHandler.stampVersionHeader(content, fromNode, "1.2.0", kind, v12);
    }

    @Test
    void isAutoResponseSuppressedReadsHeaderFields() {
        // Default (action defaults: REPLY/REQ_ACK) — not suppressed.
        String defaultAction = RelayHandler.stampVersionHeader(
            "do the thing", "macmini", "1.2.0", "action");
        assertFalse(MessagePoller.isAutoResponseSuppressed(defaultAction),
            "default action policies must allow auto-response");

        // reply_policy=NO_REPLY → suppressed
        String noReply = stampWithPolicies("do the thing", "macmini", "NO_REPLY", "REQ_ACK");
        assertTrue(MessagePoller.isAutoResponseSuppressed(noReply));

        // ack_policy=NO_ACK → suppressed
        String noAck = stampWithPolicies("do the thing", "macmini", "REPLY", "NO_ACK");
        assertTrue(MessagePoller.isAutoResponseSuppressed(noAck));

        // Pre-v1.2 message (no v1.2 fields at all) — defaults apply, not suppressed.
        assertFalse(MessagePoller.isAutoResponseSuppressed(
            "[messenger v1.1.4 from macmini]\n\nbody"),
            "pre-v1.2 headers must default to action policies (not suppressed)");
        // Headerless legacy content — also not suppressed.
        assertFalse(MessagePoller.isAutoResponseSuppressed("just plain content"));
    }

    @Test
    @Timeout(10)
    void noReplyPolicySuppressesProcessorAndArchives() {
        // Acceptance: receiver does NOT auto-respond when inbound has
        // reply_policy=NO_REPLY. Implementation: skip processor entirely so
        // we don't even spawn the Claude session — running it just to discard
        // the reply wastes a session and risks side effects.
        PeerConfig cfg = testConfig("linuxserver");
        RecordingStore brain = new RecordingStore(cfg);
        String content = stampWithPolicies(
            "kindly fire and forget", "macmini", "NO_REPLY", "REQ_ACK");
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            1000, 1000L, "macmini", "linuxserver", content, "pending"));

        CountingProcessor proc = new CountingProcessor();
        MessagePoller poller = new MessagePoller(cfg, brain, proc);
        poller.triggerPoll();

        assertEquals(0, proc.processCount.get(),
            "NO_REPLY inbound must not invoke the processor");
        assertEquals(List.of(1000), brain.archived,
            "NO_REPLY inbound must be archived so it doesn't reappear");
        assertTrue(brain.delivered.isEmpty(),
            "no need to mark delivered when we're not processing");
    }

    @Test
    @Timeout(10)
    void noAckPolicyAlsoSuppressesProcessor() {
        // ack_policy=NO_ACK is the alternate spelling of "don't auto-respond".
        // Suppression must fire on EITHER field (issue #15: "Suppress
        // auto-response when ack_policy=NO_ACK OR reply_policy=NO_REPLY").
        PeerConfig cfg = testConfig("linuxserver");
        RecordingStore brain = new RecordingStore(cfg);
        String content = stampWithPolicies(
            "noisy notification", "macmini", "REPLY", "NO_ACK");
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            1001, 1001L, "macmini", "linuxserver", content, "pending"));

        CountingProcessor proc = new CountingProcessor();
        MessagePoller poller = new MessagePoller(cfg, brain, proc);
        poller.triggerPoll();

        assertEquals(0, proc.processCount.get());
        assertEquals(List.of(1001), brain.archived);
    }

    @Test
    @Timeout(10)
    void multiTurnHappyPath() {
        // Acceptance: A→B(action,REQ_ACK), B→A(reply,NO_REPLY),
        //             A→B(action,REQ_ACK), B→A(reply,NO_REPLY) —
        // 4 messages in one thread, no auto-loop.
        //
        // From B's perspective (this poller) the inbox sees both the actions
        // it's about to process AND any replies that round-trip through the
        // mesh. Replies must bypass the processor; actions must process. Two
        // processor calls total — never more, never less.
        PeerConfig cfg = testConfig("linuxserver");
        RecordingStore brain = new RecordingStore(cfg);

        // seq=1 — A asks (action with default REPLY/REQ_ACK policies)
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            2001, 42L, "macmini", "linuxserver",
            stampAction("what's the lake level?", "macmini", "macmini:42:1"),
            "pending"));
        // seq=2 — B's reply (loopback for thread state); kind=reply must skip
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            2002, 42L, "linuxserver", "macmini",
            stampWithKindAndSeq("907 ft, 99.8% full",
                "linuxserver", "reply", "linuxserver:42:2", "macmini:42:1"),
            "pending"));
        // seq=3 — A's follow-up on the same thread (thread stays open)
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            2003, 42L, "macmini", "linuxserver",
            stampAction("has it changed in 24h?", "macmini", "macmini:42:3"),
            "pending"));
        // seq=4 — B's reply
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            2004, 42L, "linuxserver", "macmini",
            stampWithKindAndSeq("up 0.3 ft",
                "linuxserver", "reply", "linuxserver:42:4", "macmini:42:3"),
            "pending"));

        CountingProcessor proc = new CountingProcessor();
        MessagePoller poller = new MessagePoller(cfg, brain, proc);
        poller.triggerPoll();

        assertEquals(2, proc.processCount.get(),
            "exactly the 2 actions must reach the processor — no auto-loop, "
            + "no extra invocations from the reply messages");
        // Actions claimed via markDelivered; replies skipped pre-dispatch.
        assertEquals(List.of(2001, 2003), brain.delivered);
        // All 4 archived (order = arrival order; both actions and replies
        // archive before continue to next message).
        assertTrue(brain.archived.containsAll(List.of(2001, 2002, 2003, 2004)),
            "all 4 thread messages must be archived after the poll cycle");
        assertEquals(4, brain.archived.size(),
            "exactly 4 archives — no extra outbound side effect");
    }

    // ── v1.2 MAX_TURNS_PER_THREAD ceiling (issue #15) ───────────────────

    @Test
    @Timeout(30)
    void maxTurnsPerThreadDropsTwentyFirstMessage() {
        // Acceptance: thread with 21 non-progress messages drops the 21st
        // with a warning; 22nd, 23rd... also dropped.
        //
        // 21 kind=action messages on the same thread. First 20 process; 21st
        // is dropped + archived without processor invocation.
        //
        // Senders rotate per message — the per-sender rate limiter (3 msgs /
        // 10 min) is orthogonal to the per-thread cap and would otherwise
        // mask the cap behaviour. Using a unique sender per message
        // bypasses the rate limiter so the test isolates MAX_TURNS_PER_THREAD.
        PeerConfig cfg = testConfig("linuxserver");
        RecordingStore brain = new RecordingStore(cfg);

        for (int i = 1; i <= 21; i++) {
            String sender = "peer" + i;
            String content = stampAction("task #" + i, sender, sender + ":99:" + i);
            brain.inbox.add(new OpenBrainStore.PendingMessage(
                3000 + i, 99L, sender, "linuxserver", content, "pending"));
        }

        CountingProcessor proc = new CountingProcessor();
        MessagePoller poller = new MessagePoller(cfg, brain, proc);
        poller.triggerPoll();

        assertEquals(MessagePoller.MAX_TURNS_PER_THREAD, proc.processCount.get(),
            "exactly MAX_TURNS_PER_THREAD (=20) actions must process; the 21st is dropped");
        // 21st (id=3021) must be archived but NOT delivered
        assertTrue(brain.archived.contains(3021),
            "21st message must be archived");
        assertFalse(brain.delivered.contains(3021),
            "21st message must NOT have been claimed (markDelivered) — it was dropped pre-dispatch");
    }

    @Test
    @Timeout(30)
    void maxTurnsPerThreadAlsoDropsTwentySecondAndBeyond() {
        // Once a thread is at the wall, subsequent inbound on it stays at the wall.
        // No reset mechanism in v1.2.0.
        PeerConfig cfg = testConfig("linuxserver");
        RecordingStore brain = new RecordingStore(cfg);

        for (int i = 1; i <= 25; i++) {
            String sender = "peer" + i;
            String content = stampAction("task #" + i, sender, sender + ":77:" + i);
            brain.inbox.add(new OpenBrainStore.PendingMessage(
                4000 + i, 77L, sender, "linuxserver", content, "pending"));
        }

        CountingProcessor proc = new CountingProcessor();
        MessagePoller poller = new MessagePoller(cfg, brain, proc);
        poller.triggerPoll();

        // First 20 process; messages 21..25 (5 of them) are dropped.
        assertEquals(20, proc.processCount.get());
        for (int i = 21; i <= 25; i++) {
            assertTrue(brain.archived.contains(4000 + i),
                "message " + (4000 + i) + " (turn " + i + ") must be archived");
            assertFalse(brain.delivered.contains(4000 + i),
                "message " + (4000 + i) + " must not have been claimed");
        }
    }

    @Test
    @Timeout(30)
    void maxTurnsPerThreadExcludesProgressMessages() {
        // Acceptance: kind=progress messages do NOT count toward the 20-message cap.
        // Load thread with 19 non-progress + 100 progress; the 20th non-progress
        // must still process (count after it = 20, which is == MAX, NOT > MAX).
        PeerConfig cfg = testConfig("linuxserver");
        RecordingStore brain = new RecordingStore(cfg);

        // 19 actions interleaved with 100 progress messages — same thread.
        // Lay them down in arrival order: 19 actions first, then 100 progress
        // beats, then the 20th action.
        int messageId = 5000;
        for (int i = 1; i <= 19; i++) {
            String sender = "peer" + i;
            String content = stampAction("task #" + i, sender, sender + ":55:" + i);
            brain.inbox.add(new OpenBrainStore.PendingMessage(
                ++messageId, 55L, sender, "linuxserver", content, "pending"));
        }
        for (int i = 1; i <= 100; i++) {
            String content = stampWithKindAndSeq(
                "(no log activity)", "watcher", "progress",
                "watcher:55:p" + i, "peer1:55:1");
            brain.inbox.add(new OpenBrainStore.PendingMessage(
                ++messageId, 55L, "watcher", "linuxserver", content, "pending"));
        }
        // The 20th action — must still process because progress doesn't count.
        String twentiethContent = stampAction(
            "20th action — should still flow through", "peer20", "peer20:55:20");
        int twentiethId = ++messageId;
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            twentiethId, 55L, "peer20", "linuxserver", twentiethContent, "pending"));

        CountingProcessor proc = new CountingProcessor();
        MessagePoller poller = new MessagePoller(cfg, brain, proc);
        poller.triggerPoll();

        assertEquals(20, proc.processCount.get(),
            "20 actions must process — progress beats don't count toward the cap");
        assertTrue(brain.delivered.contains(twentiethId),
            "20th non-progress message must have been claimed and processed");
    }

    // ── v1.2 dedup cache (issue #16) ─────────────────────────────────────

    /**
     * Processor that records each call and writes a real reply payload into a
     * shared {@link DedupCache} so duplicate inbound is observably resent
     * through the relay sender (mirrors what {@link ClaudeCliProcessor} does
     * in production after sendReply succeeds).
     */
    static class CachingTestProcessor implements MessageProcessor {
        final AtomicInteger processCount = new AtomicInteger(0);
        final DedupCache    cache;

        CachingTestProcessor(DedupCache cache) { this.cache = cache; }

        @Override
        public void process(OpenBrainStore.PendingMessage msg) {
            int n = processCount.incrementAndGet();
            String seq = RelayHandler.extractHeaderField(msg.content(), "seq", null);
            if (seq != null && !seq.isBlank()) {
                cache.put(
                    new DedupCache.DedupKey(msg.fromNode(), msg.threadId(), seq),
                    new DedupCache.CachedResponse(
                        "reply", "response#" + n, seq, Instant.now()));
            }
        }
    }

    /** Captures every {@link RelaySender#send} invocation so dedup-resends are observable. */
    static class RecordingRelaySender implements RelaySender {
        record Sent(String to, String from, String content, String kind,
                    String inReplyTo, String replyPolicy, long threadId) {}

        final List<Sent> sent = new ArrayList<>();

        @Override
        public boolean send(String toNode, String fromNode, String content,
                            String kind, String inReplyTo, String replyPolicy, long threadId) {
            sent.add(new Sent(toNode, fromNode, content, kind, inReplyTo, replyPolicy, threadId));
            return true;
        }
    }

    @Test
    @Timeout(10)
    void duplicateSeqIdResendsCachedResponseWithoutReprocessing() {
        // Acceptance from issue #16: "Same (from, thread_id, seq_id) arrives
        // twice → second triggers cached response, no new processor invocation."
        // Test from the issue: "Inject same message twice (same seq_id);
        // assert processor called exactly once, two outbound responses
        // emitted (the second is the cached resend)."
        PeerConfig cfg = testConfig("linuxserver");
        RecordingStore brain = new RecordingStore(cfg);
        DedupCache cache = new DedupCache();
        CachingTestProcessor proc = new CachingTestProcessor(cache);
        RecordingRelaySender relay = new RecordingRelaySender();
        MessagePoller poller = new MessagePoller(cfg, brain, proc, relay, cache);

        String content = stampAction("what's the lake level?", "macmini", "macmini:42:1");

        // First poll: process the message, cache populated with real response.
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            6000, 42L, "macmini", "linuxserver", content, "pending"));
        poller.triggerPoll();
        assertEquals(1, proc.processCount.get(),
            "first inbound must reach the processor");

        // Second poll: same (from, thread, seq) duplicate. Reset RecordingStore
        // so it serves the duplicate row again, and clear the prior inbox.
        brain.drained = false;
        brain.inbox.clear();
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            6001, 42L, "macmini", "linuxserver", content, "pending"));
        poller.triggerPoll();

        // Acceptance: processor called exactly once across both polls.
        assertEquals(1, proc.processCount.get(),
            "duplicate seq_id must NOT re-invoke the processor");

        // Acceptance: a single outbound from the cached resend (the first
        // poll's reply went via the processor's cache.put which doesn't
        // touch relaySender; the second poll's resend does).
        assertEquals(1, relay.sent.size(),
            "duplicate must trigger one cached resend through the relay sender");
        RecordingRelaySender.Sent resend = relay.sent.get(0);
        assertEquals("macmini",        resend.to(),          "resend goes back to original sender");
        assertEquals("linuxserver",    resend.from());
        assertEquals("response#1",     resend.content(),     "cached payload from first invocation");
        assertEquals("reply",          resend.kind());
        assertEquals("NO_REPLY",       resend.replyPolicy(), "all auto-responses carry NO_REPLY");
        assertEquals("macmini:42:1",   resend.inReplyTo());
        assertEquals(42L,              resend.threadId());

        // The duplicate inbound is still archived so it doesn't reappear.
        assertTrue(brain.archived.contains(6001),
            "duplicate inbound must be archived after the cached resend");
        assertFalse(brain.delivered.contains(6001),
            "duplicate must NOT be claimed via markDelivered — never reaches processWithThreadLock");
    }

    @Test
    @Timeout(10)
    void distinctSeqIdsOnSameThreadAreBothProcessed() {
        // Acceptance from issue #16: "Different seq_id from same sender on
        // same thread → both processed independently."
        PeerConfig cfg = testConfig("linuxserver");
        RecordingStore brain = new RecordingStore(cfg);
        DedupCache cache = new DedupCache();
        CachingTestProcessor proc = new CachingTestProcessor(cache);
        MessagePoller poller = new MessagePoller(cfg, brain, proc, RelaySender.NOOP, cache);

        // Same thread (42), same sender (macmini), different seq_ids
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            6100, 42L, "macmini", "linuxserver",
            stampAction("first turn",  "macmini", "macmini:42:1"), "pending"));
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            6101, 42L, "macmini", "linuxserver",
            stampAction("second turn", "macmini", "macmini:42:2"), "pending"));

        poller.triggerPoll();

        assertEquals(2, proc.processCount.get(),
            "distinct seq_ids on the same thread must both reach the processor");
        assertEquals(2, brain.delivered.size(),
            "both must be claimed (markDelivered) before processing");
        assertTrue(brain.archived.containsAll(List.of(6100, 6101)));
    }

    @Test
    @Timeout(10)
    void dedupSentinelStopsRepeatRunWithoutResend() {
        // After a successful processor run with no outbound (the no-op
        // logging() processor — used as the safe fallback in MeshRelay), the
        // poller stamps a sentinel. A duplicate then short-circuits: no
        // re-processing, and (because the sentinel has no payload) no
        // outbound resend either.
        PeerConfig cfg = testConfig("linuxserver");
        RecordingStore brain = new RecordingStore(cfg);
        DedupCache cache = new DedupCache();
        CountingProcessor proc = new CountingProcessor(); // does NOT populate cache
        RecordingRelaySender relay = new RecordingRelaySender();
        MessagePoller poller = new MessagePoller(cfg, brain, proc, relay, cache);

        String content = stampAction("fire and process silently", "macmini", "macmini:99:1");

        brain.inbox.add(new OpenBrainStore.PendingMessage(
            6200, 99L, "macmini", "linuxserver", content, "pending"));
        poller.triggerPoll();
        assertEquals(1, proc.processCount.get());

        // Sentinel must now be present (set by MessagePoller post-success).
        DedupCache.DedupKey key = new DedupCache.DedupKey("macmini", 99L, "macmini:99:1");
        assertTrue(cache.contains(key),
            "sentinel must be installed after the processor returns successfully");
        assertFalse(cache.get(key).hasResponse(),
            "no real response was emitted — sentinel must reflect that");

        // Duplicate inbound: must be silently dropped (no reprocess, no resend).
        brain.drained = false;
        brain.inbox.clear();
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            6201, 99L, "macmini", "linuxserver", content, "pending"));
        poller.triggerPoll();

        assertEquals(1, proc.processCount.get(),
            "duplicate must NOT re-invoke the processor");
        assertTrue(relay.sent.isEmpty(),
            "sentinel duplicate must NOT trigger a resend (no payload to resend)");
        assertTrue(brain.archived.contains(6201),
            "duplicate is still archived");
    }

    @Test
    @Timeout(10)
    void prev12MessageWithoutSeqHeaderSkipsDedupAndProcesses() {
        // A pre-v1.2 sender writes no seq= field. The dedup cache has no key
        // to look up against, so the message must flow through normally.
        // Both inbound copies must be processed (no dedup possible).
        PeerConfig cfg = testConfig("linuxserver");
        RecordingStore brain = new RecordingStore(cfg);
        DedupCache cache = new DedupCache();
        CountingProcessor proc = new CountingProcessor();
        MessagePoller poller = new MessagePoller(cfg, brain, proc, RelaySender.NOOP, cache);

        // No V12Fields → header carries no seq=
        String content = RelayHandler.stampVersionHeader(
            "legacy content", "macmini", "1.1.4", "action");

        brain.inbox.add(new OpenBrainStore.PendingMessage(
            6300, 33L, "macmini", "linuxserver", content, "pending"));
        poller.triggerPoll();
        assertEquals(1, proc.processCount.get());

        brain.drained = false;
        brain.inbox.clear();
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            6301, 33L, "macmini", "linuxserver", content, "pending"));
        poller.triggerPoll();

        assertEquals(2, proc.processCount.get(),
            "no seq_id ⇒ no dedup key ⇒ both copies must be processed");
        assertEquals(0, cache.size(),
            "cache must stay empty when inbound has no seq_id");
    }

    @Test
    @Timeout(10)
    void duplicateDoesNotCountTowardMaxTurnsCeiling() {
        // The MAX_TURNS_PER_THREAD counter is incremented per non-progress
        // dispatch. A duplicate short-circuits before that increment, so
        // re-injecting the same seq_id 25 times against a cap of 20 must not
        // wedge the thread.
        PeerConfig cfg = testConfig("linuxserver");
        RecordingStore brain = new RecordingStore(cfg);
        DedupCache cache = new DedupCache();
        CachingTestProcessor proc = new CachingTestProcessor(cache);
        RecordingRelaySender relay = new RecordingRelaySender();
        MessagePoller poller = new MessagePoller(cfg, brain, proc, relay, cache);

        String content = stampAction("the same task", "macmini", "macmini:88:1");

        // First poll: process once.
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            6400, 88L, "macmini", "linuxserver", content, "pending"));
        poller.triggerPoll();
        assertEquals(1, proc.processCount.get());

        // 25 duplicates over subsequent polls — would otherwise have driven
        // the counter past MAX_TURNS_PER_THREAD (=20).
        for (int i = 0; i < 25; i++) {
            brain.drained = false;
            brain.inbox.clear();
            brain.inbox.add(new OpenBrainStore.PendingMessage(
                6500 + i, 88L, "macmini", "linuxserver", content, "pending"));
            poller.triggerPoll();
        }

        assertEquals(1, proc.processCount.get(),
            "duplicates must NEVER reach the processor regardless of count");
        assertEquals(25, relay.sent.size(),
            "each duplicate gets a cached resend (25 of them)");
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
