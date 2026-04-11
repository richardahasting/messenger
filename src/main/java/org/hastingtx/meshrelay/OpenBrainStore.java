package org.hastingtx.meshrelay;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");

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

        int id = extractId(response.body());
        if (id < 0) {
            throw new Exception("OpenBrain response missing 'id' field: " + response.body());
        }

        log.info("Message stored in OpenBrain: thread_id=" + id
            + " to=" + toNode + " from=" + fromNode);
        return id;
    }

    /** Build the JSON body for capture_thought using the legacy simple format. */
    private String buildCaptureJson(String fromNode, String toNode, String content) {
        // Escape the content for JSON embedding
        String escaped = content
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");

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
        java.util.List<PendingMessage> results = new java.util.ArrayList<>();

        // Query for directed messages (to:thisNode) and broadcasts (to:all)
        for (String toTag : new String[]{"to:" + nodeName, "to:all"}) {
            try {
                String body = """
                    {
                      "tool": "search_thoughts",
                      "query": "mesh message pending",
                      "mode": "keyword",
                      "project": "mesh-messages",
                      "status": "active",
                      "tags": ["%s"],
                      "limit": 50
                    }
                    """.formatted(toTag);

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(mcpUrl))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("x-brain-key", brainKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

                HttpResponse<String> response = client.send(
                    request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    results.addAll(parseMessages(response.body()));
                } else {
                    log.warning("OpenBrain poll returned HTTP " + response.statusCode());
                }
            } catch (Exception e) {
                log.warning("OpenBrain poll failed for tag=" + toTag + ": " + e.getMessage());
            }
        }

        return results;
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
     * Parse a JSON array of thought objects from an OpenBrain search response.
     * Extracts id, content, and the "from:" tag value.
     */
    private java.util.List<PendingMessage> parseMessages(String json) {
        java.util.List<PendingMessage> messages = new java.util.ArrayList<>();

        // Each thought object is bounded by { }. Extract all top-level objects.
        // We look for "id": N, "content": "...", and tags containing "from:..."
        Matcher objectMatcher = Pattern.compile(
            "\\{[^{}]*\"id\"\\s*:\\s*(\\d+)[^{}]*\\}"
        ).matcher(json);

        while (objectMatcher.find()) {
            String obj = objectMatcher.group();

            // Extract id
            Matcher idM = ID_PATTERN.matcher(obj);
            if (!idM.find()) continue;
            int id = Integer.parseInt(idM.group(1));

            // Extract content
            Matcher contentM = Pattern.compile(
                "\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(obj);
            String content = contentM.find() ? unescape(contentM.group(1)) : "";

            // Extract from: tag
            Matcher fromM = Pattern.compile("\"from:([^\"]+)\"").matcher(obj);
            String fromNode = fromM.find() ? fromM.group(1) : "unknown";

            // thread_id — present once we migrate to the messages table;
            // for now (thoughts-based) it defaults to the thought's own id.
            Matcher threadM = Pattern.compile("\"thread_id\"\\s*:\\s*(\\d+)").matcher(obj);
            long threadId = threadM.find() ? Long.parseLong(threadM.group(1)) : (long) id;

            messages.add(new PendingMessage(id, threadId, fromNode, content));
        }

        return messages;
    }

    /** Unescape basic JSON string escapes. */
    private String unescape(String s) {
        return s.replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\r", "\r")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    /** Extract the numeric "id" field from the JSON response. */
    private int extractId(String json) {
        Matcher m = ID_PATTERN.matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /**
     * A pending message retrieved from OpenBrain.
     *
     * thoughtId — the OpenBrain thought/message id (used for markArchived)
     * threadId  — conversation thread this message belongs to (used for per-thread
     *             serialization in MessagePoller). Once migrated to the messages
     *             table this will be the true thread_id; until then it equals thoughtId.
     */
    public record PendingMessage(int thoughtId, long threadId, String fromNode, String content) {}
}
