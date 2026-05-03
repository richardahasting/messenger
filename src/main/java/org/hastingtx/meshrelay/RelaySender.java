package org.hastingtx.meshrelay;

/**
 * Abstraction over "send an outbound /relay request" so daemon-internal callers
 * (e.g. the v1.2 ping auto-responder) can be tested without a live HttpServer.
 *
 * Production wires {@link HttpRelaySender}, which POSTs to localhost:&lt;port&gt;/relay
 * — the same path external callers use. Tests pass a recording stub.
 *
 * Used by {@link MessagePoller} to emit the auto-pong reply when a {@code kind=ping}
 * message arrives (see docs/protocol-v1.2.md § "Receiver behavior" step 3).
 */
public interface RelaySender {

    /**
     * Send a relay request. Returns true if the request was accepted (HTTP 200),
     * false on any failure. Failure is non-fatal — the message is already either
     * stored or about to be retried.
     *
     * @param toNode      target peer name
     * @param fromNode    sender (this node)
     * @param content     message body (the "payload")
     * @param kind        v1.2 message kind ("reply", "action", etc.)
     * @param inReplyTo   seq_id of the message being responded to (may be null)
     * @param replyPolicy "REPLY" or "NO_REPLY"
     * @param threadId    conversation thread to attach to (&lt; 0 to start a new thread)
     */
    boolean send(String toNode, String fromNode, String content,
                 String kind, String inReplyTo, String replyPolicy, long threadId);

    /** No-op implementation — silently discards every send. Used by tests that don't care. */
    RelaySender NOOP = (to, from, content, kind, inReplyTo, replyPolicy, threadId) -> true;
}
