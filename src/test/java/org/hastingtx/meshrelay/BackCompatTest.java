package org.hastingtx.meshrelay;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Backwards-compatibility unit tests for the v1.2 protocol upgrade
 * (issue #19 — see docs/protocol-v1.2.md § "Backwards compatibility" and
 * § "Loop-prevention guarantee").
 *
 * <p>Three scenarios:
 * <ol>
 *   <li>Pre-v1.2 sender → v1.2 receiver. JSON body without new fields lands
 *       cleanly. Receiver applies defaults per spec table.</li>
 *   <li>v1.2 sender → pre-v1.2 receiver (mocked). When the sender falls back
 *       to {@code kind=action} without v1.2 fields (because
 *       {@code peer.version < 1.2.0}), the wire shape is parseable by the
 *       v1.1.6 header parser and the {@code ACK_PATTERN} content guard still
 *       suppresses ack-shaped Claude output.</li>
 *   <li>Mixed mesh: linuxserver (v1.2.0) ↔ macbook-air (mocked v1.1.6). No
 *       message is rejected in either direction — the loop-prevention
 *       guarantee survives version skew.</li>
 * </ol>
 */
class BackCompatTest {

    // ─── shared fakes (shape borrowed from MessagePollerTest) ─────────────

    static class CountingProcessor implements MessageProcessor {
        final AtomicInteger processCount = new AtomicInteger(0);

        @Override
        public void process(OpenBrainStore.PendingMessage message) {
            processCount.incrementAndGet();
        }
    }

    static class RecordingStore extends OpenBrainStore {
        final List<PendingMessage> inbox = new ArrayList<>();
        final List<Integer>        archived  = new ArrayList<>();
        final List<Integer>        delivered = new ArrayList<>();
        boolean drained = false;

        RecordingStore(PeerConfig cfg) {
            super(HttpClient.newHttpClient(), cfg);
        }

        @Override
        public List<PendingMessage> pollPendingMessages(String node) {
            if (drained) return List.of();
            drained = true;
            return List.copyOf(inbox);
        }

        @Override
        public boolean markDelivered(int id) { delivered.add(id); return true; }

        @Override
        public void markArchived(int id) { archived.add(id); }

        @Override
        public void updateBroadcastWatermark(String node, int id) {}
    }

    /** Captures every {@link RelaySender#send} for assertions about outbound shape. */
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

    private static PeerConfig testConfig(String node) {
        return PeerConfig.parseConfig(
            "{\"node_name\":\"" + node + "\"}",
            node, "http://brain", "k", "test");
    }

    // ════════════════════════════════════════════════════════════════════════
    // A. Pre-v1.2 sender → v1.2 receiver
    //    (no new fields on the wire; receiver fills in spec defaults)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void preV12JsonBodyAppliesActionDefaultsOnReceive() {
        // No v1.2 fields in the body — receiver must default per spec table.
        Json json = Json.parse("{\"to\":\"macmini\",\"from\":\"linuxserver\",\"content\":\"hi\"}");
        RelayHandler.V12Fields v12 = RelayHandler.V12Fields.parseFromJson(json, "action");

        assertEquals("REQ_ACK", v12.ackPolicy(),
            "kind=action defaults to REQ_ACK when sender omits ack_policy");
        assertEquals("REPLY", v12.replyPolicy(),
            "kind=action defaults to REPLY when sender omits reply_policy");
        assertNull(v12.seqId(),       "seq stays null until daemon stamps it");
        assertNull(v12.respondBy(),   "respond_by absent → null (no hard deadline)");
        assertNull(v12.updateIntervalSeconds(), "update_interval absent → null");
        assertNull(v12.inReplyTo(),   "in_reply_to absent → null");
    }

    @Test
    void preV12JsonBodyAppliesInfoDefaultsOnReceive() {
        // v1.1.x broadcasts use kind=info, no v1.2 fields. Defaults must
        // resolve to the no-response variants so a peer never auto-responds
        // to a one-way notification.
        Json json = Json.parse("{\"to\":\"all\",\"content\":\"fyi\",\"kind\":\"info\"}");
        RelayHandler.V12Fields v12 = RelayHandler.V12Fields.parseFromJson(json, "info");

        assertEquals("NO_ACK",   v12.ackPolicy());
        assertEquals("NO_REPLY", v12.replyPolicy());
    }

    @Test
    void preV12JsonBodyAppliesAckDefaultsOnReceive() {
        // v1.1.x kind=ack pre-dates the policy fields entirely. Defaults
        // must resolve so the receiver never reflexively re-acks.
        Json json = Json.parse("{\"to\":\"linuxserver\",\"content\":\"\",\"kind\":\"ack\"}");
        RelayHandler.V12Fields v12 = RelayHandler.V12Fields.parseFromJson(json, "ack");

        assertEquals("NO_ACK",   v12.ackPolicy());
        assertEquals("NO_REPLY", v12.replyPolicy());
    }

    @Test
    @Timeout(10)
    void preV12HeaderActionMessageRunsProcessorOnV12Receiver() {
        // Wire-format check: a stored content with the legacy
        // [messenger v1.1.6 from old kind=action] header (no v1.2 trailing
        // tokens) still flows through the dispatch path on a v1.2 receiver.
        PeerConfig cfg = testConfig("linuxserver");
        RecordingStore brain = new RecordingStore(cfg);
        String content = RelayHandler.stampVersionHeader(
            "do the thing", "macbook-air", "1.1.6", "action");
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            500, 500L, "macbook-air", "linuxserver", content, "pending"));

        CountingProcessor proc = new CountingProcessor();
        new MessagePoller(cfg, brain, proc).triggerPoll();

        assertEquals(1, proc.processCount.get(),
            "pre-v1.2 action message must run processor unchanged");
    }

    @Test
    @Timeout(10)
    void preV12HeaderlessMessageDefaultsToActionAndRuns() {
        // Pre-1.1.0 sender (no version header at all) — receiver must default
        // to kind=action so legacy messages aren't silently dropped.
        PeerConfig cfg = testConfig("linuxserver");
        RecordingStore brain = new RecordingStore(cfg);
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            501, 501L, "macbook-air", "linuxserver", "plain legacy body", "pending"));

        CountingProcessor proc = new CountingProcessor();
        new MessagePoller(cfg, brain, proc).triggerPoll();

        assertEquals(1, proc.processCount.get(),
            "headerless legacy message must fall back to kind=action");
    }

    @Test
    @Timeout(10)
    void preV12AckMessageBypassesProcessorOnV12Receiver() {
        // v1.1.x kind=ack must still archive without a Claude session — that
        // was the v1.1.3 short-circuit fix; v1.2 must not regress it.
        PeerConfig cfg = testConfig("linuxserver");
        RecordingStore brain = new RecordingStore(cfg);
        String content = RelayHandler.stampVersionHeader(
            "Roger that.", "macbook-air", "1.1.6", "ack");
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            502, 502L, "macbook-air", "linuxserver", content, "pending"));

        CountingProcessor proc = new CountingProcessor();
        new MessagePoller(cfg, brain, proc).triggerPoll();

        assertEquals(0, proc.processCount.get(),
            "pre-v1.2 ack must not reach processor");
        assertEquals(List.of(502), brain.archived);
        assertTrue(brain.delivered.isEmpty(),
            "kind=ack short-circuit must skip markDelivered");
    }

    // ════════════════════════════════════════════════════════════════════════
    // B. v1.2 sender → pre-v1.2 receiver (mocked)
    //    Spec § "Backwards compatibility": when peer.version < 1.2.0 the
    //    sender MUST fall back to kind=action with no v1.2 trailing fields.
    //    The ACK_PATTERN content guard from v1.1.5/v1.1.6 stays active.
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void v12FallbackHeaderParsableByLegacyPattern() {
        // Wire shape of the fallback: stamp a header WITHOUT V12Fields so the
        // trailer contains nothing v1.1.6 wouldn't recognise. The legacy
        // HEADER_PATTERN must still extract version, from, and kind cleanly.
        String fallback = RelayHandler.stampVersionHeader(
            "do the thing", "linuxserver", "1.2.0", "action");

        var m = RelayHandler.HEADER_PATTERN.matcher(fallback);
        assertTrue(m.find(), "v1.2 fallback header must match the legacy regex");
        assertEquals("1.2.0",       m.group(1), "version still in slot 1");
        assertEquals("linuxserver", m.group(2), "from still in slot 2");
        assertEquals("action",  RelayHandler.extractKind(fallback));
        assertEquals("do the thing", RelayHandler.extractBody(fallback));

        // None of the v1.2 trailing tokens may appear when the sender takes
        // the fallback path — that is the whole point of the fallback.
        assertFalse(fallback.contains("seq="),         "fallback must omit seq=");
        assertFalse(fallback.contains("ack="),         "fallback must omit ack=");
        assertFalse(fallback.contains("reply="),       "fallback must omit reply=");
        assertFalse(fallback.contains("respond_by="),  "fallback must omit respond_by=");
        assertFalse(fallback.contains("update="),      "fallback must omit update=");
        assertFalse(fallback.contains("in_reply_to="), "fallback must omit in_reply_to=");
    }

    @Test
    void ackPatternStillMatchesAckShapedClaudeOutput() {
        // Defense-in-depth from v1.1.5/v1.1.6 must remain active across the
        // full v1.2 line (per spec § "Migration plan": "ACK_PATTERN content
        // guards stay in place during rollout. Removed in v1.3").
        // ClaudeCliProcessor.isAcknowledgement() is the static guard; if a
        // v1.2 sender forgets to fall back AND Claude reflexively echoes
        // "noop — ack received" to a pre-v1.2 peer, the processor must drop
        // the reply rather than let it loop.
        assertTrue(ClaudeCliProcessor.isAcknowledgement("noop"));
        assertTrue(ClaudeCliProcessor.isAcknowledgement("ack received"));
        assertTrue(ClaudeCliProcessor.isAcknowledgement("noop — ack received"));
        assertTrue(ClaudeCliProcessor.isAcknowledgement("noop - ack received"));
        assertTrue(ClaudeCliProcessor.isAcknowledgement("Noop — Ack Received"),
            "case-insensitive match required for v1.1.5/v1.1.6 parity");
        assertFalse(ClaudeCliProcessor.isAcknowledgement("the lake is at 99.8%"));
        assertFalse(ClaudeCliProcessor.isAcknowledgement(""));
        assertFalse(ClaudeCliProcessor.isAcknowledgement(null));
    }

    @Test
    void v12FieldsInHeaderAreIgnoredByLegacyKindExtraction() {
        // Pre-v1.2 receiver only inspects the kind field (or its absence).
        // Even if a v1.2 sender forgot to fall back and stamped all v1.2
        // fields, the legacy kind extraction must still produce the correct
        // value and the body must come out clean. extractKind is the v1.1.x
        // receiver's only inspection point, so this is the entire contract.
        RelayHandler.V12Fields v12 = new RelayHandler.V12Fields(
            "ls:42:1", "REQ_ACK", "REPLY", "2026-05-04T10:00:00Z", 60, null);
        String header = RelayHandler.stampVersionHeader(
            "body", "linuxserver", "1.2.0", "action", v12);

        assertEquals("action", RelayHandler.extractKind(header));
        assertEquals("body",   RelayHandler.extractBody(header));
    }

    // ════════════════════════════════════════════════════════════════════════
    // C. Mixed mesh — linuxserver (v1.2.0) ↔ macbook-air (mocked v1.1.6)
    //    No message rejected in either direction.
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @Timeout(10)
    void v12LinuxserverActionDispatchesOnV116MockMacbookAir() {
        // The "v1.1.6 mock" is a poller running with the same dispatch logic;
        // the message wire shape is what a v1.2 sender SHOULD produce when
        // talking to a v1.1.6 peer (no v1.2 trailing fields). The dispatcher
        // must handle it identically to a real v1.1.x message.
        PeerConfig cfg = testConfig("macbook-air");
        RecordingStore brain = new RecordingStore(cfg);
        String content = RelayHandler.stampVersionHeader(
            "restart foo", "linuxserver", "1.2.0", "action");
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            600, 600L, "linuxserver", "macbook-air", content, "pending"));

        CountingProcessor proc = new CountingProcessor();
        new MessagePoller(cfg, brain, proc).triggerPoll();

        assertEquals(1, proc.processCount.get(),
            "v1.2 → mocked v1.1.6: action must dispatch");
        assertEquals(List.of(600), brain.archived,
            "message must be archived after processing");
    }

    @Test
    @Timeout(10)
    void v116MockMacbookAirActionRunsOnV12Linuxserver() {
        // Reverse direction: v1.1.6 sender → v1.2 receiver. v1.1.x stamps no
        // v1.2 fields; the v1.2 receiver applies defaults and dispatches.
        PeerConfig cfg = testConfig("linuxserver");
        RecordingStore brain = new RecordingStore(cfg);
        String content = RelayHandler.stampVersionHeader(
            "what's the lake level?", "macbook-air", "1.1.6", "action");
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            601, 601L, "macbook-air", "linuxserver", content, "pending"));

        CountingProcessor proc = new CountingProcessor();
        new MessagePoller(cfg, brain, proc).triggerPoll();

        assertEquals(1, proc.processCount.get(),
            "mocked v1.1.6 → v1.2: action must run processor with defaults");
        assertEquals(List.of(601), brain.archived);
    }

    @Test
    @Timeout(10)
    void v12FullFieldMessageStillRunsThroughV12Dispatch() {
        // Sanity guard for the same-version path — a fully stamped v1.2
        // outbound is parseable end-to-end by a v1.2 receiver. If this
        // regresses, mixed-mesh same-version assumptions break too.
        PeerConfig cfg = testConfig("linuxserver");
        RecordingStore brain = new RecordingStore(cfg);
        RelayHandler.V12Fields v12 = new RelayHandler.V12Fields(
            "macbook-air:42:1", "REQ_ACK", "REPLY", null, null, null);
        String content = RelayHandler.stampVersionHeader(
            "ping the lake gauge", "macbook-air", "1.2.0", "action", v12);
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            602, 602L, "macbook-air", "linuxserver", content, "pending"));

        CountingProcessor proc = new CountingProcessor();
        new MessagePoller(cfg, brain, proc).triggerPoll();

        assertEquals(1, proc.processCount.get(),
            "fully stamped v1.2 action must dispatch normally");
    }

    @Test
    @Timeout(10)
    void v12NoReplyMessageIsSuppressedOnReceive() {
        // Loop-prevention guarantee (spec § "Loop-prevention guarantee"):
        // a reply_policy=NO_REPLY message MUST NOT elicit any auto-response.
        // If a v1.2 sender stamps NO_REPLY (typical for a kind=reply), the
        // v1.2 receiver must suppress processor invocation entirely.
        PeerConfig cfg = testConfig("linuxserver");
        RecordingStore brain = new RecordingStore(cfg);
        RelayHandler.V12Fields v12 = new RelayHandler.V12Fields(
            "macbook-air:42:2", "REQ_ACK", "NO_REPLY", null, null, null);
        // kind=action with explicit NO_REPLY — the suppression path. The
        // poller must archive without dispatch.
        String content = RelayHandler.stampVersionHeader(
            "loop-prevention probe", "macbook-air", "1.2.0", "action", v12);
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            603, 603L, "macbook-air", "linuxserver", content, "pending"));

        CountingProcessor proc = new CountingProcessor();
        new MessagePoller(cfg, brain, proc).triggerPoll();

        assertEquals(0, proc.processCount.get(),
            "NO_REPLY action must NEVER reach the processor");
        assertEquals(List.of(603), brain.archived);
    }

    @Test
    void mixedMeshAllKindsAreClassifiedCorrectly() {
        // Spec § "kind enum (extended)": every kind a v1.2 sender can emit
        // must be classified by the v1.2 receiver, and the v1.1.x kinds
        // (action/ack/info) must remain known. If a v1.2 sender forgot to
        // fall back and stamped kind=reply/progress/ping to a pre-v1.2
        // receiver, the pre-v1.2 receiver would archive it as unknown —
        // but the v1.2 sender's job is to never put the peer in that
        // position. This is the symmetric-classification guard.
        for (String kind : List.of("reply", "ack", "progress", "info", "ping")) {
            assertTrue(MessagePoller.isKnownNonActionKind(kind),
                "v1.2 receiver must classify '" + kind + "' as known");
        }
        // Sanity: 'action' is not in the non-action set (it runs the processor)
        assertFalse(MessagePoller.isKnownNonActionKind("action"));
        // Drift guard: an unrecognised kind stays unrecognised.
        assertFalse(MessagePoller.isKnownNonActionKind("sneaky_new_kind"));
    }
}
