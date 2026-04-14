package org.hastingtx.meshrelay;

/**
 * Pluggable handler for a pending message retrieved from OpenBrain.
 *
 * Current implementation: logs the message and returns.
 * Future implementations:
 *   - GemmaProcessor:  forward to local Ollama Gemma4 for triage/simple responses
 *   - ClaudeProcessor: invoke claude -p with message as prompt + OpenBrain context
 *   - RouterProcessor: classify first, then route to Gemma4 or Claude
 *
 * The poller marks the message archived AFTER process() returns without exception.
 * Throw any exception to signal "not processed" — poller will retry next cycle.
 */
@FunctionalInterface
public interface MessageProcessor {

    void process(OpenBrainStore.PendingMessage message) throws Exception;

    /** Default: log the message. Swap this out for real processing. */
    static MessageProcessor logging() {
        return msg -> {
            System.out.printf("[MessageProcessor] thread_id=%d from=%s content=%s%n",
                msg.threadId(), msg.fromNode(),
                msg.content().length() > 120
                    ? msg.content().substring(0, 120) + "…"
                    : msg.content());
        };
    }
}
