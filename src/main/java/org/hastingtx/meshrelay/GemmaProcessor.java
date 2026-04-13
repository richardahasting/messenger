package org.hastingtx.meshrelay;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * MessageProcessor that sends incoming messages to a local Gemma model via
 * Ollama and relays the response back to the sender.
 *
 * Uses the Ollama /api/chat endpoint (stream=false).
 * Zero external cost — all inference runs locally on macmini.
 *
 * Configuration (env vars or .env file):
 *   OLLAMA_URL   — Ollama base URL  (default: http://192.168.0.226:11434)
 *   OLLAMA_MODEL — model to use     (default: gemma4:e4b)
 *
 * Activation priority in MeshRelay.main():
 *   1. GemmaProcessor  — if Ollama is reachable (free, local, preferred)
 *   2. ClaudeProcessor — if ANTHROPIC_API_KEY is set (costs money, use sparingly)
 *   3. logging()       — fallback if neither is available
 */
public class GemmaProcessor implements MessageProcessor {

    private static final Logger log = Logger.getLogger(GemmaProcessor.class.getName());

    private static final String DEFAULT_OLLAMA_URL   = "http://192.168.0.226:11434";
    private static final String DEFAULT_OLLAMA_MODEL = "gemma4:e4b";
    private static final Duration INFERENCE_TIMEOUT  = Duration.ofMinutes(10);
    private static final Duration RELAY_TIMEOUT      = Duration.ofSeconds(15);

    private final HttpClient http;
    private final PeerConfig config;
    private final String     ollamaUrl;
    private final String     model;
    private final String     chatEndpoint;
    private final String     relayUrl;

    private GemmaProcessor(HttpClient http, PeerConfig config,
                            String ollamaUrl, String model) {
        this.http         = http;
        this.config       = config;
        this.ollamaUrl    = ollamaUrl;
        this.model        = model;
        this.chatEndpoint = ollamaUrl + "/api/chat";
        this.relayUrl     = "http://localhost:" + config.listenPort + "/relay";
    }

    /**
     * Build a GemmaProcessor if Ollama is reachable, otherwise return null.
     * Caller should fall through to the next processor option on null.
     */
    public static MessageProcessor create(HttpClient http, PeerConfig config) {
        String ollamaUrl = System.getenv("OLLAMA_URL");
        if (ollamaUrl == null || ollamaUrl.isBlank()) ollamaUrl = DEFAULT_OLLAMA_URL;

        String model = System.getenv("OLLAMA_MODEL");
        if (model == null || model.isBlank()) model = DEFAULT_OLLAMA_MODEL;

        // Quick liveness check — don't start if Ollama isn't reachable
        try {
            HttpRequest ping = HttpRequest.newBuilder()
                .uri(URI.create(ollamaUrl + "/api/tags"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
            HttpResponse<Void> resp = http.send(ping, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() != 200) throw new Exception("HTTP " + resp.statusCode());
        } catch (Exception e) {
            log.warning("Ollama not reachable at " + ollamaUrl + " — " + e.getMessage());
            return null;
        }

        log.info("GemmaProcessor active — ollama=" + ollamaUrl + " model=" + model
            + " node=" + config.nodeName);
        return new GemmaProcessor(http, config, ollamaUrl, model);
    }

    @Override
    public void process(OpenBrainStore.PendingMessage msg) throws Exception {
        log.info("Processing message via Gemma — thread_id=" + msg.threadId()
            + " from=" + msg.fromNode());

        String reply = callGemma(msg.fromNode(), msg.threadId(), msg.content());
        sendReply(msg.fromNode(), reply, msg.threadId());

        log.info("Reply sent to " + msg.fromNode() + " thread_id=" + msg.threadId());
    }

    private String callGemma(String fromNode, long threadId, String userContent) throws Exception {
        String systemPrompt = SystemPrompt.build(config, fromNode, threadId, false);

        String body = "{"
            + "\"model\":\"" + Json.escape(model) + "\","
            + "\"stream\":false,"
            + "\"messages\":["
            + "{\"role\":\"system\",\"content\":\"" + Json.escape(systemPrompt) + "\"},"
            + "{\"role\":\"user\",\"content\":\"" + Json.escape(userContent) + "\"}"
            + "]}";

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(chatEndpoint))
            .header("Content-Type", "application/json")
            .timeout(INFERENCE_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new Exception("Ollama returned HTTP " + response.statusCode()
                + ": " + response.body().substring(0, Math.min(200, response.body().length())));
        }

        return extractReplyText(response.body());
    }

    private void sendReply(String toNode, String replyContent, long threadId) throws Exception {
        String body = "{\"to\":\"" + toNode + "\","
            + "\"from\":\"" + config.nodeName + "\","
            + "\"content\":\"" + Json.escape(replyContent) + "\","
            + "\"thread_id\":" + threadId + "}";

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(relayUrl))
            .header("Content-Type", "application/json")
            .timeout(RELAY_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new Exception("Local relay returned HTTP " + response.statusCode());
        }
    }

    /** Extract assistant content from Ollama /api/chat response. */
    private String extractReplyText(String json) {
        String content = Json.parse(json).get("message").getString("content");
        if (content == null) throw new IllegalStateException("No content in Ollama response: "
            + json.substring(0, Math.min(300, json.length())));
        return content;
    }
}
