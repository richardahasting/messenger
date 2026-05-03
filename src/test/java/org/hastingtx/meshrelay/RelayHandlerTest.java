package org.hastingtx.meshrelay;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the v1.2 wire-format additions to RelayHandler.
 *
 * Issue #12 — Wire format additions: parse seq_id, ack_policy, reply_policy,
 * respond_by, update_interval_seconds, in_reply_to from inbound /relay JSON.
 * Stamp the same fields onto the stored content header. Reject 400 on
 * kind=reply/ack/progress without in_reply_to.
 *
 * No behavior change in this issue — fields are parsed, defaulted, and stamped.
 * Subsequent issues consume them (#13 dispatch, #15 NO_REPLY, #16 dedup, #17
 * progress).
 *
 * See docs/protocol-v1.2.md "Wire format" for the field table.
 */
class RelayHandlerTest {

    // ── parseFromJson: full v1.2 body ─────────────────────────────────────

    @Test
    void parseV12BodyExtractsAllFields() {
        String body = """
            {
              "to": "macmini",
              "from": "linuxserver",
              "content": "do the thing",
              "kind": "action",
              "thread_id": 42,
              "seq_id": "linuxserver:42:1",
              "ack_policy": "REQ_ACK",
              "reply_policy": "REPLY",
              "respond_by": "2026-05-04T10:00:00Z",
              "update_interval_seconds": 60,
              "in_reply_to": "macmini:42:0"
            }
            """;
        Json json = Json.parse(body);
        RelayHandler.V12Fields v12 = RelayHandler.V12Fields.parseFromJson(json, "action");

        assertEquals("linuxserver:42:1", v12.seqId());
        assertEquals("REQ_ACK", v12.ackPolicy());
        assertEquals("REPLY", v12.replyPolicy());
        assertEquals("2026-05-04T10:00:00Z", v12.respondBy());
        assertEquals(Integer.valueOf(60), v12.updateIntervalSeconds());
        assertEquals("macmini:42:0", v12.inReplyTo());
    }

    // ── parseFromJson: v1.1 body (no new fields) → defaults ───────────────

    @Test
    void parseV11BodyAppliesActionDefaults() {
        String body = """
            {
              "to": "macmini",
              "from": "linuxserver",
              "content": "v1.1 message",
              "kind": "action"
            }
            """;
        Json json = Json.parse(body);
        RelayHandler.V12Fields v12 = RelayHandler.V12Fields.parseFromJson(json, "action");

        assertNull(v12.seqId(),       "seq_id stays null until daemon stamps it");
        assertEquals("REQ_ACK", v12.ackPolicy());
        assertEquals("REPLY",   v12.replyPolicy());
        assertNull(v12.respondBy());
        assertNull(v12.updateIntervalSeconds());
        assertNull(v12.inReplyTo());
    }

    @Test
    void parseV11BodyAppliesInfoDefaults() {
        // v1.1 body with kind=info — defaults should be NO_ACK / NO_REPLY
        Json json = Json.parse("{\"to\":\"all\",\"content\":\"fyi\",\"kind\":\"info\"}");
        RelayHandler.V12Fields v12 = RelayHandler.V12Fields.parseFromJson(json, "info");
        assertEquals("NO_ACK",   v12.ackPolicy());
        assertEquals("NO_REPLY", v12.replyPolicy());
    }

    @Test
    void parseV11BodyDefaultsToActionWhenKindAbsent() {
        // Caller of parseFromJson passes the effective kind; "action" is the
        // implicit default upstream.
        Json json = Json.parse("{\"to\":\"macmini\",\"content\":\"hi\"}");
        RelayHandler.V12Fields v12 = RelayHandler.V12Fields.parseFromJson(json, "action");
        assertEquals("REQ_ACK", v12.ackPolicy());
        assertEquals("REPLY",   v12.replyPolicy());
    }

    // ── per-kind defaults from the spec table ─────────────────────────────

    @Test
    void defaultAckPolicyMatchesSpecTable() {
        assertEquals("REQ_ACK", RelayHandler.V12Fields.defaultAckPolicy("action"));
        assertEquals("REQ_ACK", RelayHandler.V12Fields.defaultAckPolicy("ping"));
        assertEquals("NO_ACK",  RelayHandler.V12Fields.defaultAckPolicy("info"));
        assertEquals("NO_ACK",  RelayHandler.V12Fields.defaultAckPolicy("reply"));
        assertEquals("NO_ACK",  RelayHandler.V12Fields.defaultAckPolicy("ack"));
        assertEquals("NO_ACK",  RelayHandler.V12Fields.defaultAckPolicy("progress"));
    }

    @Test
    void defaultReplyPolicyMatchesSpecTable() {
        assertEquals("REPLY",    RelayHandler.V12Fields.defaultReplyPolicy("action"));
        assertEquals("REPLY",    RelayHandler.V12Fields.defaultReplyPolicy("ping"));
        assertEquals("NO_REPLY", RelayHandler.V12Fields.defaultReplyPolicy("info"));
        assertEquals("NO_REPLY", RelayHandler.V12Fields.defaultReplyPolicy("reply"));
        assertEquals("NO_REPLY", RelayHandler.V12Fields.defaultReplyPolicy("ack"));
        assertEquals("NO_REPLY", RelayHandler.V12Fields.defaultReplyPolicy("progress"));
    }

    // ── in_reply_to mandatory for reply/ack/progress ──────────────────────

    @Test
    void requiresInReplyToForReplyAckProgress() {
        assertTrue(RelayHandler.V12Fields.requiresInReplyTo("reply"));
        assertTrue(RelayHandler.V12Fields.requiresInReplyTo("ack"));
        assertTrue(RelayHandler.V12Fields.requiresInReplyTo("progress"));
        assertFalse(RelayHandler.V12Fields.requiresInReplyTo("action"));
        assertFalse(RelayHandler.V12Fields.requiresInReplyTo("info"));
        assertFalse(RelayHandler.V12Fields.requiresInReplyTo("ping"));
    }

    // ── seq_id stamping ───────────────────────────────────────────────────

    @Test
    void seqIdStampingFollowsFromThreadCounterPattern() {
        // Constructor takes (HttpClient, PeerConfig, OpenBrainStore) but
        // stampDefaultSeqId touches none of them — safe to pass nulls.
        RelayHandler h = new RelayHandler(null, null, null);
        String seq = h.stampDefaultSeqId("linuxserver", 42L);

        // Spec: "<from>:<thread>:<counter>"
        assertTrue(seq.matches("linuxserver:42:\\d+"),
            "seq_id must match <from>:<thread>:<n>; got: " + seq);
        assertEquals("linuxserver:42:1", seq, "first counter value should be 1");
    }

    @Test
    void seqIdCounterIsMonotonicPerThread() {
        RelayHandler h = new RelayHandler(null, null, null);
        assertEquals("a:1:1", h.stampDefaultSeqId("a", 1L));
        assertEquals("a:1:2", h.stampDefaultSeqId("a", 1L));
        assertEquals("a:1:3", h.stampDefaultSeqId("a", 1L));
    }

    @Test
    void seqIdCountersAreIndependentPerFromThreadKey() {
        RelayHandler h = new RelayHandler(null, null, null);
        assertEquals("a:1:1", h.stampDefaultSeqId("a", 1L));
        assertEquals("b:1:1", h.stampDefaultSeqId("b", 1L)); // different from
        assertEquals("a:2:1", h.stampDefaultSeqId("a", 2L)); // different thread
        assertEquals("a:1:2", h.stampDefaultSeqId("a", 1L)); // continues a:1
    }

    // ── header stamping with v1.2 fields ──────────────────────────────────

    @Test
    void stampVersionHeaderIncludesSeqAndInReplyTo() {
        RelayHandler.V12Fields v12 = new RelayHandler.V12Fields(
            "linuxserver:42:1", "REQ_ACK", "REPLY", null, null, "macmini:42:0");
        String out = RelayHandler.stampVersionHeader(
            "body", "linuxserver", "1.2.0", "reply", v12);
        assertTrue(out.contains("seq=linuxserver:42:1"),       "header missing seq: " + out);
        assertTrue(out.contains("in_reply_to=macmini:42:0"),   "header missing in_reply_to: " + out);
        assertTrue(out.contains("kind=reply"),                  "header missing kind: " + out);
        assertTrue(out.contains("ack=REQ_ACK"),                "header missing ack: " + out);
        assertTrue(out.contains("reply=REPLY"),                "header missing reply: " + out);
        assertTrue(out.endsWith("]\n\nbody"));
    }

    @Test
    void stampVersionHeaderOmitsAbsentV12Fields() {
        RelayHandler.V12Fields v12 = new RelayHandler.V12Fields(
            "linuxserver:42:1", "REQ_ACK", "REPLY", null, null, null);
        String out = RelayHandler.stampVersionHeader(
            "body", "linuxserver", "1.2.0", "action", v12);
        assertFalse(out.contains("in_reply_to="), "absent in_reply_to must not appear");
        assertFalse(out.contains("respond_by="),  "absent respond_by must not appear");
        assertFalse(out.contains("update="),      "absent update_interval must not appear");
    }

    @Test
    void stampVersionHeaderWithV12FieldsRoundTripsThroughExtractKind() {
        // Backwards compat: even with new fields appended, extractKind must
        // still work via the updated HEADER_PATTERN.
        RelayHandler.V12Fields v12 = new RelayHandler.V12Fields(
            "ls:42:1", "NO_ACK", "NO_REPLY", null, null, "mm:42:0");
        String stamped = RelayHandler.stampVersionHeader(
            "payload", "linuxserver", "1.2.0", "reply", v12);
        assertEquals("reply", RelayHandler.extractKind(stamped));
        assertEquals("payload", RelayHandler.extractBody(stamped));
    }

    // ── HEADER_PATTERN backwards compatibility ────────────────────────────

    @Test
    void headerPatternStillMatchesV11OneLineHeader() {
        // Pre-v1.2 daemon emits no v1.2 fields, no kind.
        var m = RelayHandler.HEADER_PATTERN.matcher("[messenger v1.1.6 from linuxserver]\n\nbody");
        assertTrue(m.find());
        assertEquals("1.1.6", m.group(1));
        assertEquals("linuxserver", m.group(2));
        assertEquals("", m.group(3));   // empty trailer
    }

    @Test
    void headerPatternStillMatchesV11WithKind() {
        var m = RelayHandler.HEADER_PATTERN.matcher(
            "[messenger v1.1.6 from linuxserver kind=ack]\n\nbody");
        assertTrue(m.find());
        assertEquals("1.1.6", m.group(1));
        assertEquals("linuxserver", m.group(2));
        Map<String, String> fields = RelayHandler.parseHeaderFields(m.group(3));
        assertEquals("ack", fields.get("kind"));
    }

    @Test
    void headerPatternMatchesV12FullHeader() {
        var m = RelayHandler.HEADER_PATTERN.matcher(
            "[messenger v1.2.0 from linuxserver kind=reply seq=ls:42:1 in_reply_to=mm:42:0]\n\nbody");
        assertTrue(m.find());
        assertEquals("1.2.0", m.group(1));
        assertEquals("linuxserver", m.group(2));
        Map<String, String> fields = RelayHandler.parseHeaderFields(m.group(3));
        assertEquals("reply",     fields.get("kind"));
        assertEquals("ls:42:1",   fields.get("seq"));
        assertEquals("mm:42:0",   fields.get("in_reply_to"));
    }

    @Test
    void parseHeaderFieldsHandlesEmptyTrailer() {
        assertTrue(RelayHandler.parseHeaderFields("").isEmpty());
        assertTrue(RelayHandler.parseHeaderFields(null).isEmpty());
        assertTrue(RelayHandler.parseHeaderFields("   ").isEmpty());
    }

    @Test
    void parseHeaderFieldsHandlesMultipleTokens() {
        Map<String, String> fields = RelayHandler.parseHeaderFields(
            " kind=reply seq=ls:42:1 ack=NO_ACK reply=NO_REPLY in_reply_to=mm:42:0");
        assertEquals("reply",     fields.get("kind"));
        assertEquals("ls:42:1",   fields.get("seq"));
        assertEquals("NO_ACK",    fields.get("ack"));
        assertEquals("NO_REPLY",  fields.get("reply"));
        assertEquals("mm:42:0",   fields.get("in_reply_to"));
    }

    // ── extractHeaderField ────────────────────────────────────────────────

    @Test
    void extractHeaderFieldReturnsValue() {
        String content = "[messenger v1.2.0 from linuxserver kind=reply seq=ls:42:1]\n\nhi";
        assertEquals("ls:42:1", RelayHandler.extractHeaderField(content, "seq", "default"));
    }

    @Test
    void extractHeaderFieldReturnsDefaultWhenAbsent() {
        String content = "[messenger v1.1.6 from linuxserver]\n\nhi";
        assertEquals("default", RelayHandler.extractHeaderField(content, "seq", "default"));
    }

    @Test
    void extractHeaderFieldReturnsDefaultOnMalformedInput() {
        assertEquals("default", RelayHandler.extractHeaderField(null, "kind", "default"));
        assertEquals("default", RelayHandler.extractHeaderField("no header here", "kind", "default"));
    }

    // ── round-trip: pre-v1.2 envelope passes through unchanged ────────────

    @Test
    void preV12HeaderRoundTrips() {
        // A pre-v1.2 message stored by an old daemon. After re-parsing, the
        // header must still extract correctly and the body must come out clean.
        String stored = "[messenger v1.1.6 from linuxserver kind=info]\n\nbroadcast text";
        assertEquals("info", RelayHandler.extractKind(stored));
        assertEquals("broadcast text", RelayHandler.extractBody(stored));
    }
}
