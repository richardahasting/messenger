package org.hastingtx.meshrelay;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MessageProcessor that delegates to the Claude Code CLI (claude -p).
 *
 * Mirrors the Python agent-mesh claude_agent.py approach exactly:
 *   - Shells out to: claude -p [--resume <session_id>] <message>
 *                    --model claude-sonnet-4-6
 *                    --output-format json
 *                    --dangerously-skip-permissions
 *   - Uses the Claude subscription (Max plan) — zero API credit cost.
 *   - Gets full agentic capabilities: bash, file I/O, MCP tools.
 *
 * Session continuity:
 *   Sessions are keyed by thread_id (not fromNode). Each messenger
 *   conversation thread maps to one persistent Claude session — Claude
 *   remembers the full thread context without re-sending history.
 *
 * Activation: requires the claude CLI to be on PATH or at known locations.
 * Falls back gracefully if the binary is not found.
 */
public class ClaudeCliProcessor implements MessageProcessor {

    private static final Logger log = Logger.getLogger(ClaudeCliProcessor.class.getName());

    private static final String MODEL   = "claude-sonnet-4-6";
    private static final int    TIMEOUT_MINUTES = 12;   // matches thread lock timeout

    /** thread_id → Claude session_id for conversation continuity. */
    private final ConcurrentHashMap<Long, String> sessions = new ConcurrentHashMap<>();

    private final HttpClient http;
    private final PeerConfig config;
    private final String     claudeBin;
    private final String     relayUrl;

    private ClaudeCliProcessor(HttpClient http, PeerConfig config, String claudeBin) {
        this.http      = http;
        this.config    = config;
        this.claudeBin = claudeBin;
        this.relayUrl  = "http://localhost:" + config.listenPort + "/relay";
    }

    /**
     * Build a ClaudeCliProcessor if the claude CLI is reachable, else null.
     * Caller falls through to the next processor option on null.
     */
    public static MessageProcessor create(HttpClient http, PeerConfig config) {
        String bin = findClaudeBin();
        if (bin == null) {
            log.warning("claude CLI not found — ClaudeCliProcessor unavailable");
            return null;
        }
        log.info("ClaudeCliProcessor active — bin=" + bin + " model=" + MODEL
            + " node=" + config.nodeName);
        return new ClaudeCliProcessor(http, config, bin);
    }

    @Override
    public void process(OpenBrainStore.PendingMessage msg) throws Exception {
        log.info("Processing via claude CLI — thread_id=" + msg.threadId()
            + " from=" + msg.fromNode());

        String reply = runClaude(msg.threadId(), msg.fromNode(), msg.content());
        sendReply(msg.fromNode(), reply, msg.threadId());

        log.info("Reply sent — thread_id=" + msg.threadId() + " to=" + msg.fromNode());
    }

    private String runClaude(long threadId, String fromNode, String userContent) throws Exception {
        String existingSession = sessions.get(threadId);

        // Build system prompt for first message in this thread
        String systemPrompt = "You are the " + config.nodeName
            + " agent in a distributed multi-agent system. "
            + "You are receiving a message from the " + fromNode + " agent. "
            + "Respond helpfully and concisely. "
            + "You have full tool access — use bash for any system commands requested.";

        List<String> cmd = new ArrayList<>();
        cmd.add(claudeBin);
        cmd.add("-p");

        if (existingSession != null) {
            cmd.add("--resume");
            cmd.add(existingSession);
            cmd.add(userContent);
        } else {
            cmd.add(systemPrompt + "\n\n" + userContent);
        }

        cmd.add("--model");         cmd.add(MODEL);
        cmd.add("--output-format"); cmd.add("json");
        cmd.add("--dangerously-skip-permissions");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().putAll(buildEnv());
        pb.redirectErrorStream(false);

        Process proc = pb.start();

        // Read stdout fully — claude -p streams JSON to stdout
        byte[] stdoutBytes = proc.getInputStream().readAllBytes();
        String stdout = new String(stdoutBytes, StandardCharsets.UTF_8).trim();

        boolean finished = proc.waitFor(TIMEOUT_MINUTES, java.util.concurrent.TimeUnit.MINUTES);
        if (!finished) {
            proc.destroyForcibly();
            throw new Exception("claude CLI timed out after " + TIMEOUT_MINUTES + "m");
        }

        return parseCliOutput(stdout, threadId);
    }

    private String parseCliOutput(String stdout, long threadId) {
        if (stdout.isEmpty()) return "[no output]";

        // claude --output-format json returns: {"result":"...","session_id":"..."}
        Matcher sessionM = Pattern.compile("\"session_id\"\\s*:\\s*\"([^\"]+)\"").matcher(stdout);
        if (sessionM.find()) {
            String sid = sessionM.group(1);
            sessions.put(threadId, sid);
            log.fine("Session stored thread_id=" + threadId + " session=" + sid);
        }

        Matcher resultM = Pattern.compile("\"result\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(stdout);
        if (resultM.find()) {
            return resultM.group(1)
                .replace("\\n", "\n").replace("\\t", "\t")
                .replace("\\r", "\r").replace("\\\"", "\"")
                .replace("\\\\", "\\");
        }

        // Fallback: return raw stdout if JSON parse fails
        return stdout.length() > 2000 ? stdout.substring(0, 2000) + "…" : stdout;
    }

    private void sendReply(String toNode, String content, long threadId) throws Exception {
        String body = "{\"to\":\"" + toNode + "\","
            + "\"from\":\"" + config.nodeName + "\","
            + "\"content\":\"" + jsonEscape(content) + "\","
            + "\"thread_id\":" + threadId + "}";

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(relayUrl))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200)
            throw new Exception("Local relay returned HTTP " + resp.statusCode());
    }

    /** Locate the claude CLI binary — mirrors Python's _find_claude_cli(). */
    private static String findClaudeBin() {
        // 1. System PATH
        for (String dir : System.getenv("PATH").split(":")) {
            Path p = Path.of(dir, "claude");
            if (Files.isExecutable(p)) return p.toString();
        }
        // 2. Known fixed locations
        for (String candidate : List.of(
                System.getProperty("user.home") + "/.local/bin/claude",
                "/usr/local/bin/claude",
                "/opt/homebrew/bin/claude")) {
            Path p = Path.of(candidate);
            if (Files.isExecutable(p)) return p.toString();
        }
        // 3. nvm node_modules (macOS/Linux nvm installs)
        Path nvmNodes = Path.of(System.getProperty("user.home"), ".nvm/versions/node");
        if (Files.isDirectory(nvmNodes)) {
            try {
                return Files.list(nvmNodes)
                    .sorted(java.util.Comparator.reverseOrder())
                    .map(v -> v.resolve("bin/claude"))
                    .filter(Files::isExecutable)
                    .map(Path::toString)
                    .findFirst()
                    .orElse(null);
            } catch (IOException ignored) {}
        }
        return null;
    }

    /** Build subprocess environment — ensures nvm node bin is on PATH. */
    private java.util.Map<String, String> buildEnv() {
        java.util.Map<String, String> env = new java.util.HashMap<>(System.getenv());
        // Add nvm bin dir if present (mirrors Python's PATH extension)
        Path nvmNodes = Path.of(System.getProperty("user.home"), ".nvm/versions/node");
        if (Files.isDirectory(nvmNodes)) {
            try {
                Files.list(nvmNodes)
                    .sorted(java.util.Comparator.reverseOrder())
                    .map(v -> v.resolve("bin").toString())
                    .filter(d -> Files.isDirectory(Path.of(d)))
                    .findFirst()
                    .ifPresent(d -> env.merge("PATH", d, (old, n) -> n + ":" + old));
            } catch (IOException ignored) {}
        }
        String localBin = System.getProperty("user.home") + "/.local/bin";
        env.merge("PATH", localBin, (old, n) -> n + ":" + old);
        return env;
    }

    private String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
