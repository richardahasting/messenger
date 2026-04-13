package org.hastingtx.meshrelay;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * Writes mesh messages to OpenBrain as thoughts.
 *
 * Uses the OpenBrain legacy simple API format:
 *   POST /mcp  {tool: "capture_thought", content: "...", tags: [...], ...}
 *   Header: x-brain-key: <key>
 *
 * Returns the thought ID, which becomes the thread_id in the wake-up notification.
 * The full message content lives in OpenBrain permanently — the relay only
 * carries the thread_id from this point on.
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

    /**
     * Store a mesh message as a thought and return its ID (thread_id).
     *
     * The thought is tagged so the recipient can find it:
     *   tags:     ["to:<toNode>", "from:<fromNode>", "mesh-message"]
     *   category: relationship
     *   project:  mesh-messages
     *   status:   active  ← unread; recipient sets to archived when processed
     *   node:     <fromNode>
     *
     * @throws Exception if OpenBrain is unreachable or returns an error.
     *         Callers should treat this as a hard failure — no thread_id means
     *         the wake-up cannot be sent either.
     */
    public int storeMessage(String fromNode, String toNode, String content) throws Exception {
        String body = buildCaptureJson(fromNode, toNode, content);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(mcpUrl))
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .header("x-brain-key", brainKey)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new Exception("OpenBrain returned HTTP " + response.statusCode()
                + ": " + response.body());
        }

        int id = Json.parse(response.body()).getInt("id", -1);
        if (id < 0) {
            throw new Exception("OpenBrain response missing 'id' field: " + response.body());
        }

        log.info("Message stored in OpenBrain: thread_id=" + id
            + " to=" + toNode + " from=" + fromNode);
        return id;
    }

    /** Build the JSON body for capture_thought using the legacy simple format. */
    private String buildCaptureJson(String fromNode, String toNode, String content) {
        String escaped = Json.escape(content);

        return """
            {
              "tool": "capture_thought",
              "content": "%s",
              "category": "relationship",
              "project": "mesh-messages",
              "node": "%s",
              "status": "active",
              "tags": ["to:%s", "from:%s", "mesh-message"],
              "source": "mesh-relay"
            }
            """.formatted(escaped, fromNode, toNode, fromNode);
    }

    /**
     * Query OpenBrain for all pending messages addressed to this node.
     *
     * Searches for thoughts with:
     *   project: mesh-messages
     *   status:  active
     *   tags:    ["to:<nodeName>"] OR ["to:all"]  (broadcasts included)
     *
     * Returns a list of pending messages, oldest first.
     */
    public java.util.List<PendingMessage> pollPendingMessages(String nodeName) {
        // browse_thoughts does pure SQL filtering — no text search, no false misses.
        // We fetch all active mesh-messages and filter by to:nodeName / to:all client-side.
        try {
            String body = """
                {
                  "tool": "browse_thoughts",
                  "project": "mesh-messages",
                  "status": "active",
                  "limit": 50
                }
                """;

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
                log.warning("OpenBrain poll returned HTTP " + response.statusCode());
                return java.util.List.of();
            }

            // Filter client-side: keep only messages addressed to this node or broadcast
            return parseMessages(response.body()).stream()
                .filter(m -> m.toNode().equals(nodeName) || m.toNode().equals("all"))
                .collect(java.util.stream.Collectors.toList());

        } catch (Exception e) {
            log.warning("OpenBrain poll failed: " + e.getMessage());
            return java.util.List.of();
        }
    }

    /**
     * Mark a thought as archived — signals "this message has been processed".
     * Called after the agent successfully handles a pending message.
     */
    public void markArchived(int thoughtId) {
        try {
            String body = """
                {"tool":"update_thought","id":%d,"status":"archived"}
                """.formatted(thoughtId);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(mcpUrl))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("x-brain-key", brainKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

            client.send(request, HttpResponse.BodyHandlers.discarding());
            log.fine("Marked thought " + thoughtId + " as archived");
        } catch (Exception e) {
            log.warning("Failed to archive thought " + thoughtId + ": " + e.getMessage());
        }
    }

    /**
     * Parse a JSON array of thought objects from an OpenBrain response.
     * Extracts id, content, and the "from:"/"to:" tag values.
     */
    private java.util.List<PendingMessage> parseMessages(String json) {
        java.util.List<PendingMessage> messages = new java.util.ArrayList<>();
        Json thoughts = Json.parse(json);
        if (!thoughts.isArray()) return messages;

        for (Json thought : thoughts.asList()) {
            int id = thought.getInt("id", -1);
            if (id < 0) continue;

            String content  = thought.getString("content", "");
            String fromNode = "unknown";
            String toNode   = "all";

            for (Json tag : thought.get("tags").asList()) {
                String t = tag.asString();
                if (t.startsWith("from:")) fromNode = t.substring(5);
                else if (t.startsWith("to:")) toNode = t.substring(3);
            }

            long threadId = thought.has("thread_id")
                ? thought.getLong("thread_id") : (long) id;

            messages.add(new PendingMessage(id, threadId, fromNode, toNode, content));
        }

        return messages;
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

    /**
     * A pending message retrieved from OpenBrain.
     *
     * thoughtId — the OpenBrain thought/message id (used for markArchived)
     * threadId  — conversation thread this message belongs to (used for per-thread
     *             serialization in MessagePoller). Once migrated to the messages
     *             table this will be the true thread_id; until then it equals thoughtId.
     */
    public record PendingMessage(int thoughtId, long threadId, String fromNode, String toNode, String content) {}
}
