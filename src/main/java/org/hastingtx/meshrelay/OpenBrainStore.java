package org.hastingtx.meshrelay;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * Writes and reads mesh messages via the OpenBrain messages table.
 *
 * Uses the OpenBrain MCP HTTP API:
 *   POST /mcp  {tool: "send_message"|"get_inbox"|"mark_delivered"|"mark_archived", ...}
 *   Header: x-brain-key: <key>
 *
 * Migration note: this class was previously backed by the thoughts table
 * (capture_thought / browse_thoughts / update_thought). It now uses the
 * purpose-built messages table, which provides native thread_id tracking,
 * inbox-style retrieval, and proper delivery state.
 *
 * KNOWN LIMITATION: there is no "reset to pending" in the messages table API.
 * If processing fails after markDelivered(), the message stays in "delivered"
 * state and will not be retried automatically. See MessagePoller for details.
 *
 * FIXED (2026-04-13): send_message previously returned None on macmini due to a
 * PostgreSQL CTE snapshot-isolation bug in tools/messaging.py. The outer UPDATE
 * in the CTE could not see the just-inserted row, so fetchrow() returned None and
 * row["id"] raised TypeError. Fixed by replacing the CTE with two separate
 * statements inside an explicit asyncpg transaction. Service redeployed on macmini.
 * storeMessage() now receives the correct {id, thread_id} envelope.
 */
public class OpenBrainStore {

    private static final Logger log = Logger.getLogger(OpenBrainStore.class.getName());
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client;
    private final String mcpUrl;   // e.g. http://192.168.0.226:3000/mcp
    private final String brainKey;

    public OpenBrainStore(HttpClient client, PeerConfig config) {
        this.client   = client;
        this.mcpUrl   = config.openBrainUrl + "/mcp";
        this.brainKey = config.openBrainKey;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Sending
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Store a mesh message via the messages table and return both IDs.
     *
     * Calls send_message which should return {id, thread_id}:
     *   id        — the row id used for mark_delivered / mark_archived
     *   thread_id — the conversation thread (used for wake-up pings and
     *               per-thread serialization in MessagePoller)
     *
     * When threadId >= 0, the message is stored as a reply in that thread.
     * If OpenBrain rejects the thread_id (FK violation — stale/phantom thread),
     * the method automatically retries without thread_id to start a new thread.
     * This makes reply delivery robust to thread table gaps.
     *
     * @throws Exception if OpenBrain is unreachable, returns a non-200 status,
     *         or returns a malformed envelope (the currently-broken None case).
     *         Callers should treat this as a hard failure — no StoreResult means
     *         the wake-up cannot be sent either.
     */
    public StoreResult storeMessage(String fromNode, String toNode, String content) throws Exception {
        return storeMessage(fromNode, toNode, content, -1L);
    }

    /**
     * Store a mesh message in an existing thread. If threadId < 0, a new thread is started.
     * Automatically falls back to a new thread if the given threadId is rejected by OpenBrain.
     */
    public StoreResult storeMessage(String fromNode, String toNode, String content,
                                    long threadId) throws Exception {
        String threadField = threadId > 0
            ? ",\n  \"thread_id\": " + threadId
            : "";

        String body = """
            {
              "tool": "send_message",
              "from_node": "%s",
              "to_node": "%s",
              "content": "%s"%s
            }
            """.formatted(fromNode, toNode, Json.escape(content), threadField);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(mcpUrl))
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .header("x-brain-key", brainKey)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            // If the caller supplied a thread_id and OpenBrain rejected it (FK violation on a
            // phantom/stale thread), retry once as a new thread rather than failing hard.
            if (threadId >= 0) {
                log.warning("send_message with thread_id=" + threadId + " rejected (HTTP "
                    + response.statusCode() + ") — retrying as new thread. Body: "
                    + response.body().substring(0, Math.min(200, response.body().length())));
                return storeMessage(fromNode, toNode, content, -1L);
            }
            throw new Exception("OpenBrain send_message returned HTTP " + response.statusCode()
                + ": " + response.body());
        }

        Json result      = Json.parse(response.body());
        int  messageId   = result.getInt("id", -1);
        long assignedThread = result.getLong("thread_id", -1L);

        if (messageId < 0) {
            // This is the currently-broken case: server returns None → parser sees no "id".
            throw new Exception("send_message response missing 'id' field — server returned: "
                + response.body());
        }
        if (assignedThread <= 0) {
            throw new Exception("send_message response missing 'thread_id' field — server returned: "
                + response.body());
        }

        log.info("Message stored in OpenBrain: message_id=" + messageId
            + " thread_id=" + assignedThread + " to=" + toNode + " from=" + fromNode);
        return new StoreResult(messageId, assignedThread);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Receiving
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Query OpenBrain for all pending messages addressed to this node.
     *
     * Uses get_inbox which returns direct messages plus unread broadcasts.
     * Server-side filtering replaces the client-side tag scan used with
     * the thoughts table.
     *
     * Returns an empty list (never throws) so a temporary OpenBrain outage
     * just pauses delivery without crashing the polling loop.
     */
    public java.util.List<PendingMessage> pollPendingMessages(String nodeName) {
        try {
            String body = """
                {
                  "tool": "get_inbox",
                  "node": "%s",
                  "include_broadcasts": true,
                  "limit": 50
                }
                """.formatted(nodeName);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(mcpUrl))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("x-brain-key", brainKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> response = client.send(
                request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warning("OpenBrain get_inbox returned HTTP " + response.statusCode());
                return java.util.List.of();
            }

            return parseInboxMessages(response.body());

        } catch (Exception e) {
            log.warning("OpenBrain poll failed: " + e.getMessage());
            return java.util.List.of();
        }
    }

    /**
     * Mark a message as delivered (pending → delivered) before processing begins.
     *
     * This is the atomic "claim" step — once delivered, subsequent get_inbox
     * calls will not return this message, preventing duplicate processing.
     *
     * NOTE: Unlike the previous claimMessage() / resetMessage() pair, the
     * messages table has no "reset to pending" operation. If processing fails
     * after markDelivered(), the message stays in "delivered" state. See
     * MessagePoller for how this case is handled.
     *
     * Returns true if the server accepted the update, false on network error.
     * A false return means the message should be skipped this cycle.
     */
    public boolean markDelivered(int messageId) {
        try {
            String body = """
                {"tool":"mark_delivered","message_id":%d}
                """.formatted(messageId);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(mcpUrl))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("x-brain-key", brainKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warning("Failed to mark message " + messageId + " delivered: HTTP " + resp.statusCode());
                return false;
            }
            log.fine("Marked message " + messageId + " as delivered");
            return true;
        } catch (Exception e) {
            log.warning("Failed to mark message " + messageId + " delivered: " + e.getMessage());
            return false;
        }
    }

    /**
     * Reset a "delivered" message back to "pending" for retry.
     *
     * NOT IMPLEMENTED — the OpenBrain messages API has no mark_pending operation.
     * This stub documents the required capability and owns the correct call site.
     * When macmini adds mark_pending to the OpenBrain MCP, replace the body with:
     *
     *   String body = "{\"tool\":\"mark_pending\",\"message_id\":%d}".formatted(messageId);
     *   POST to mcpUrl with brainKey header
     *   return resp.statusCode() == 200;
     *
     * Callers check the return value:
     *   true  → message is pending again; will be retried on the next poll
     *   false → reset unavailable; caller falls through to the dead-letter path
     *
     * Feature request filed: OpenBrain thought #mark-pending-feature-request
     * See ARCHITECTURE.md §4.3 for the failure contract this enables.
     *
     * @return false always (not implemented)
     */
    public boolean resetDelivered(int messageId) {
        // TODO: implement when macmini adds mark_pending to the messages API
        log.warning("resetDelivered() not implemented — message_id=" + messageId
            + " remains 'delivered'; falling through to dead-letter path");
        return false;
    }

    /**
     * Archive a message — signals "processing complete, do not re-deliver".
     * Called after the agent successfully handles a pending message.
     * Accepts any message status (pending, delivered, or already archived).
     */
    public void markArchived(int messageId) {
        try {
            String body = """
                {"tool":"mark_archived","message_id":%d}
                """.formatted(messageId);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(mcpUrl))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("x-brain-key", brainKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

            client.send(request, HttpResponse.BodyHandlers.discarding());
            log.fine("Marked message " + messageId + " as archived");
        } catch (Exception e) {
            log.warning("Failed to archive message " + messageId + ": " + e.getMessage());
        }
    }

    /**
     * Advance this node's broadcast watermark past the given message id.
     *
     * For broadcast messages (to_node="all"), get_inbox uses a per-node
     * watermark — not the row status — to decide what's "new". Calling
     * markArchived() on a broadcast row changes the shared row status but
     * does NOT advance the watermark, so the message keeps re-appearing on
     * every poll. This method fixes that.
     *
     * Must be called after successfully processing any message with toNode=="all".
     */
    public void updateBroadcastWatermark(String nodeName, int messageId) {
        try {
            String body = """
                {"tool":"update_broadcast_watermark","node":"%s","last_seen_id":%d}
                """.formatted(nodeName, messageId);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(mcpUrl))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("x-brain-key", brainKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

            client.send(request, HttpResponse.BodyHandlers.discarding());
            log.info("Broadcast watermark advanced: node=" + nodeName + " last_seen_id=" + messageId);
        } catch (Exception e) {
            log.warning("Failed to update broadcast watermark for message_id=" + messageId
                + ": " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Session context (thoughts table — unchanged)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Store a dead-letter record in OpenBrain when message processing fails.
     *
     * The message has already been archived (cleared from "delivered" limbo)
     * before this is called. This thought provides an auditable record so
     * the failure can be investigated or replayed manually.
     *
     * Uses the thoughts table (not messages) — no status transitions, never
     * re-delivered. Tagged for easy lookup: dead-letter + thread:<id>.
     */
    public void storeDeadLetter(PendingMessage msg, String errorReason) {
        try {
            String content = Json.escape(
                "Dead-letter: processing failed for message_id=" + msg.messageId()
                + " thread_id=" + msg.threadId()
                + " from=" + msg.fromNode()
                + " to=" + msg.toNode()
                + " error=" + (errorReason != null ? errorReason : "unknown")
                + " content_preview=" + msg.content().substring(0, Math.min(200, msg.content().length())));

            String body = """
                {
                  "tool": "capture_thought",
                  "content": "%s",
                  "category": "dead-letter",
                  "project": "messenger",
                  "status": "active",
                  "tags": ["dead-letter", "thread:%d", "from:%s"],
                  "source": "messenger-poller"
                }
                """.formatted(content, msg.threadId(), Json.escape(msg.fromNode()));

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(mcpUrl))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("x-brain-key", brainKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

            client.send(request, HttpResponse.BodyHandlers.discarding());
            log.warning("Dead-letter stored in OpenBrain for message_id=" + msg.messageId()
                + " thread_id=" + msg.threadId());
        } catch (Exception e) {
            // Best-effort — if OpenBrain is also down, log locally and move on.
            log.warning("Failed to store dead-letter for message_id=" + msg.messageId()
                + ": " + e.getMessage());
        }
    }

    /**
     * Store a session summary in OpenBrain for later context restoration.
     * Tagged so it can be retrieved by thread_id when a new session starts.
     */
    public void storeSessionSummary(long threadId, String nodeName, String summary) {
        try {
            String escaped = Json.escape(summary);
            String body = """
                {
                  "tool": "capture_thought",
                  "content": "%s",
                  "category": "session-context",
                  "project": "messenger",
                  "node": "%s",
                  "status": "active",
                  "tags": ["thread:%d", "session-summary"],
                  "source": "messenger-session"
                }
                """.formatted(escaped, nodeName, threadId);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(mcpUrl))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("x-brain-key", brainKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

            client.send(request, HttpResponse.BodyHandlers.discarding());
            log.info("Session summary stored for thread_id=" + threadId);
        } catch (Exception e) {
            log.warning("Failed to store session summary for thread_id=" + threadId + ": " + e.getMessage());
        }
    }

    /**
     * Fetch the most recent session summary for a thread from OpenBrain.
     * Returns null if no prior context exists.
     */
    public String fetchSessionContext(long threadId) {
        try {
            String body = """
                {
                  "tool": "search_thoughts",
                  "query": "thread:%d session-summary",
                  "mode": "keyword",
                  "limit": 1
                }
                """.formatted(threadId);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(mcpUrl))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("x-brain-key", brainKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;

            Json results = Json.parse(response.body());
            if (!results.isArray() || results.size() == 0) return null;

            return results.asList().get(0).getString("content");
        } catch (Exception e) {
            log.warning("Failed to fetch session context for thread_id=" + threadId + ": " + e.getMessage());
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Parsing
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Parse messages from a get_inbox response.
     *
     * Handles two possible response shapes from OpenBrain:
     *   1. Flat array:  [{id, thread_id, from_node, to_node, content}, ...]
     *   2. Structured:  {direct: [...], broadcasts: [...]}
     *
     * If the server adds new shapes in future, this method will return an
     * empty list rather than throw, so the poller degrades gracefully.
     */
    private java.util.List<PendingMessage> parseInboxMessages(String json) {
        java.util.List<PendingMessage> messages = new java.util.ArrayList<>();
        Json root = Json.parse(json);

        if (root.isArray()) {
            for (Json msg : root.asList()) {
                PendingMessage pm = parseOneMessage(msg);
                if (pm != null) messages.add(pm);
            }
        } else if (root.isObject()) {
            // {direct: [...], broadcasts: [...]}
            for (Json msg : root.get("direct").asList()) {
                PendingMessage pm = parseOneMessage(msg);
                if (pm != null) messages.add(pm);
            }
            for (Json msg : root.get("broadcasts").asList()) {
                PendingMessage pm = parseOneMessage(msg);
                if (pm != null) messages.add(pm);
            }
        }

        return messages;
    }

    private PendingMessage parseOneMessage(Json msg) {
        int  id       = msg.getInt("id", -1);
        long threadId = msg.getLong("thread_id", -1L);
        if (id < 0) return null;
        // If thread_id is absent (e.g. broadcast with no thread), fall back to message id
        if (threadId < 0) threadId = id;

        String fromNode = msg.getString("from_node", "unknown");
        String toNode   = msg.getString("to_node",   "all");
        String content  = msg.getString("content",   "");
        String status   = msg.getString("status",    "pending");
        return new PendingMessage(id, threadId, fromNode, toNode, content, status);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Value types
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Result of storeMessage(): the message row id and its conversation thread id.
     *
     * messageId — used for markDelivered() / markArchived()
     * threadId  — used for wake-up ping content and per-thread serialization
     */
    public record StoreResult(int messageId, long threadId) {}

    /**
     * A pending message retrieved from the inbox.
     *
     * messageId — the messages table row id (used for markDelivered / markArchived)
     * threadId  — conversation thread (used for per-thread serialization in MessagePoller)
     */
    public record PendingMessage(int messageId, long threadId, String fromNode, String toNode, String content, String status) {}
}
