package org.hastingtx.meshrelay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
