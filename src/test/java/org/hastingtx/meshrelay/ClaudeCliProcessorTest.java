package org.hastingtx.meshrelay;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the doom-loop quick guard in {@link ClaudeCliProcessor#isAcknowledgement}.
 *
 * Background: messenger#9 — every inbound message used to trigger a Claude CLI run,
 * including replies and acks. The system prompt instructs Claude to reply
 * "noop — ack received" for ack-shaped inputs, so the receiving peer would run
 * Claude on that ack, generate another ack, and the loop persisted indefinitely.
 *
 * The guard short-circuits {@code process()} when the inbound body matches the
 * acknowledgement pattern, regardless of whether the sender stamped kind=ack.
 */
class ClaudeCliProcessorTest {

    @Test
    void noopAlone() {
        assertTrue(ClaudeCliProcessor.isAcknowledgement("noop"));
    }

    @Test
    void ackReceivedAlone() {
        assertTrue(ClaudeCliProcessor.isAcknowledgement("ack received"));
    }

    @Test
    void noopEmDashAckReceived() {
        // The exact form Claude emits when SystemPrompt.java tells it to ack.
        assertTrue(ClaudeCliProcessor.isAcknowledgement("noop — ack received"));
    }

    @Test
    void noopEmDashAck() {
        assertTrue(ClaudeCliProcessor.isAcknowledgement("noop — ack"));
    }

    @Test
    void noopHyphenAckReceived() {
        // Hyphen variant in case Claude (or a peer) drops the em-dash.
        assertTrue(ClaudeCliProcessor.isAcknowledgement("noop - ack received"));
    }

    @Test
    void caseInsensitive() {
        assertTrue(ClaudeCliProcessor.isAcknowledgement("NOOP"));
        assertTrue(ClaudeCliProcessor.isAcknowledgement("Ack Received"));
        assertTrue(ClaudeCliProcessor.isAcknowledgement("Noop — Ack Received"));
    }

    @Test
    void leadingWhitespaceTolerated() {
        // extractBody can leave a stray newline; trim() handles it.
        assertTrue(ClaudeCliProcessor.isAcknowledgement("\n\nnoop — ack received"));
        assertTrue(ClaudeCliProcessor.isAcknowledgement("   ack received"));
    }

    @Test
    void substantiveRequestNotAcked() {
        assertFalse(ClaudeCliProcessor.isAcknowledgement(
            "write me an essay about the music of Vienna"));
    }

    @Test
    void questionNotAcked() {
        assertFalse(ClaudeCliProcessor.isAcknowledgement(
            "What is the status of the deploy?"));
    }

    @Test
    void wordsThatStartWithAckButArentAcksMustNotMatch() {
        // \b word boundary keeps "acknowledge the queue" from matching
        // because the token after "ack" isn't a literal " received".
        assertFalse(ClaudeCliProcessor.isAcknowledgement("acknowledge the queue depth"));
        assertFalse(ClaudeCliProcessor.isAcknowledgement("noopener attribute is on the link"));
    }

    @Test
    void emptyAndNullSafelyFalse() {
        assertFalse(ClaudeCliProcessor.isAcknowledgement(""));
        assertFalse(ClaudeCliProcessor.isAcknowledgement(null));
        assertFalse(ClaudeCliProcessor.isAcknowledgement("   "));
    }

    @Test
    void ackInsideLongerBodyDoesNotMatch() {
        // The pattern is anchored to start-of-body (after trim). A long message
        // that *contains* the word "noop" mid-paragraph must still go to Claude.
        assertFalse(ClaudeCliProcessor.isAcknowledgement(
            "Here is the report you asked for. The system was a noop today."));
    }

    // ─────────────────────────────────────────────────────────────────────
    // v1.1.6 — same predicate, now also called on Claude's *outbound* reply
    // before sendReply(). Cases below document the output-side use: an
    // ack-shaped reply Claude generated must be dropped, while normal replies
    // (a "pong", a real answer, the [no output] fallback) must pass through.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void outputFilter_claudeReflexiveAckIsDropped() {
        // Exactly what SystemPrompt.java:110 instructs Claude to emit when an
        // ack-shaped input reaches it. The output filter must catch this.
        assertTrue(ClaudeCliProcessor.isAcknowledgement("noop — ack received"));
    }

    @Test
    void outputFilter_pongReplyPassesThrough() {
        // The verification ping expects gemma-small to reply "pong" and
        // linuxserver/macmini/macbook-air to NOT reflex-ack it. The output
        // guard must let any non-ack reply through. (The defensive check is
        // that *Claude's* reply isn't ack-shaped, but a "pong" from Claude
        // would equally need to pass.)
        assertFalse(ClaudeCliProcessor.isAcknowledgement("pong"));
    }

    @Test
    void outputFilter_noOutputFallbackPassesThrough() {
        // parseCliOutput returns "[no output]" when the CLI produced empty
        // stdout. That string is not ack-shaped and should be sent so the
        // peer knows something happened.
        assertFalse(ClaudeCliProcessor.isAcknowledgement("[no output]"));
    }

    @Test
    void outputFilter_substantiveReplyPassesThrough() {
        // A real Claude answer must be sent regardless of length or content.
        assertFalse(ClaudeCliProcessor.isAcknowledgement(
            "The deploy completed at 14:32 UTC. All three nodes are reporting "
            + "healthy. Logs are clean — no errors in the last hour."));
    }

    // ─────────────────────────────────────────────────────────────────────
    // v1.2 progress-beat helpers (issue #17). The end-to-end progress flow
    // is exercised in ProgressBeatSchedulerTest; these focus on the parsing
    // and path-construction helpers that ClaudeCliProcessor.process() uses
    // to decide whether and where to set up the per-thread log.
    // ─────────────────────────────────────────────────────────────────────

    private static String stampedHeader(Integer updateInterval, String seqId) {
        RelayHandler.V12Fields v12 = new RelayHandler.V12Fields(
            seqId, "REQ_ACK", "REPLY", null, updateInterval, null);
        return RelayHandler.stampVersionHeader(
            "do something interesting", "macmini", "1.2.0", "action", v12);
    }

    @Test
    void parseUpdateInterval_returnsClampInputWhenPresent() {
        // process() reads the raw value; ProgressBeatScheduler.start applies
        // the [30, 120] clamp. Pinning the parser separately makes the
        // clamp/parse split visible.
        assertEquals(60, ClaudeCliProcessor.parseUpdateInterval(
            stampedHeader(60, "macmini:42:1")));
        assertEquals(45, ClaudeCliProcessor.parseUpdateInterval(
            stampedHeader(45, "macmini:42:2")));
    }

    @Test
    void parseUpdateInterval_returnsNullWhenAbsent() {
        // Pre-v1.2 sender, or v1.2 sender that didn't request progress beats.
        // null means "skip the progress-log setup entirely" in process().
        RelayHandler.V12Fields noUpdate = new RelayHandler.V12Fields(
            "macmini:42:3", "REQ_ACK", "REPLY", null, null, null);
        String content = RelayHandler.stampVersionHeader(
            "ping me", "macmini", "1.2.0", "action", noUpdate);
        assertNull(ClaudeCliProcessor.parseUpdateInterval(content));
    }

    @Test
    void parseUpdateInterval_returnsNullWhenHeaderMalformed() {
        // Defensive: header field exists but isn't an integer. Returning null
        // routes process() through the no-progress path instead of throwing.
        String forged = "[messenger v1.2.0 from macmini update=NOT_AN_INT]\n\nbody";
        assertNull(ClaudeCliProcessor.parseUpdateInterval(forged));
    }

    @Test
    void parseUpdateInterval_returnsNullForLegacyHeaderlessContent() {
        // Pre-v1.0 stored content with no version header at all.
        assertNull(ClaudeCliProcessor.parseUpdateInterval("plain message body, no header"));
        assertNull(ClaudeCliProcessor.parseUpdateInterval(null));
    }

    @Test
    void progressLogPath_matchesSpecFormat() {
        // Spec § "Receiver-side flow":
        //   logPath = /var/run/messenger/progress/thread-<id>-seq-<seq>.log
        Path p = ClaudeCliProcessor.progressLogPath(42L, "macmini:42:1");
        assertEquals(Path.of("/var/run/messenger/progress/thread-42-seq-macmini:42:1.log"), p);
    }

    @Test
    void progressLogPath_handlesNullSeq() {
        // Defensive: process() guards against null seq before calling, but
        // the helper itself must not blow up on null — used in error paths.
        Path p = ClaudeCliProcessor.progressLogPath(7L, null);
        assertTrue(p.toString().endsWith("thread-7-seq-0.log"),
            "null seq → '0' placeholder so the helper is total: " + p);
    }
}
