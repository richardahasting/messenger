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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
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
 * Session lifecycle:
 *   1. Active session — thread_id maps to a live Claude session_id (--resume)
 *   2. Expiry — session unused for sessionTtlMinutes is eligible for eviction
 *   3. Before eviction — summarize the conversation via Claude, store in OpenBrain
 *   4. New session for expired thread — load prior summary from OpenBrain as context
 *
 * Activation: requires the claude CLI to be on PATH or at known locations.
 * Falls back gracefully if the binary is not found.
 */
public class ClaudeCliProcessor implements MessageProcessor {

    private static final Logger log = Logger.getLogger(ClaudeCliProcessor.class.getName());

    @Override
    public String name() { return "claude-cli"; }

    private final String model;
    private final int    timeoutMinutes;
    private final int    sessionTtlMinutes;
    private final int    reaperIntervalMinutes;

    /** Tracks a Claude session and when it was last used. */
    record SessionEntry(String sessionId, Instant lastUsed) {}

    /** thread_id → session entry for conversation continuity. */
    private final ConcurrentHashMap<Long, SessionEntry> sessions = new ConcurrentHashMap<>();

    private final HttpClient      http;
    private final PeerConfig      config;
    private final OpenBrainStore  brain;
    private final String          claudeBin;
    private final String          relayUrl;

    private ClaudeCliProcessor(HttpClient http, PeerConfig config,
                                OpenBrainStore brain, String claudeBin) {
        this.http                  = http;
        this.config                = config;
        this.brain                 = brain;
        this.claudeBin             = claudeBin;
        this.relayUrl              = "http://localhost:" + config.listenPort + "/relay";
        this.model                 = config.claudeModel;
        this.timeoutMinutes        = config.claudeTimeoutMinutes;
        this.sessionTtlMinutes     = config.sessionTtlMinutes;
        this.reaperIntervalMinutes = config.reaperIntervalMinutes;
        startSessionReaper();
    }

    /** Background thread that evicts expired sessions, summarizing first. */
    private void startSessionReaper() {
        Thread.ofVirtual().name("session-reaper").start(() -> {
            while (true) {
                try {
                    Thread.sleep(Duration.ofMinutes(reaperIntervalMinutes));
                    reapExpiredSessions();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        log.info("Session reaper started — TTL=" + sessionTtlMinutes + "m, check interval="
            + reaperIntervalMinutes + "m");
    }

    private void reapExpiredSessions() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(sessionTtlMinutes));

        for (Map.Entry<Long, SessionEntry> entry : sessions.entrySet()) {
            if (entry.getValue().lastUsed().isBefore(cutoff)) {
                long threadId = entry.getKey();
                SessionEntry session = sessions.remove(threadId);
                if (session == null) continue; // already removed by another thread

                log.info("Session expired — thread_id=" + threadId
                    + " idle since " + session.lastUsed());

                // Best-effort: summarize before evicting
                try {
                    String summary = summarizeSession(session.sessionId());
                    if (summary != null && !summary.isBlank()) {
                        brain.storeSessionSummary(threadId, config.nodeName, summary);
                    }
                } catch (Exception e) {
                    log.warning("Failed to summarize session thread_id=" + threadId
                        + ": " + e.getMessage());
                }
            }
        }
    }

    /**
     * Ask Claude to summarize the session before we discard it.
     * Uses --resume to access the full conversation, then extracts the summary.
     */
    private String summarizeSession(String sessionId) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(claudeBin);
            cmd.add("-p");
            cmd.add("--resume");
            cmd.add(sessionId);
            cmd.add(SystemPrompt.summarize());
            cmd.add("--model");         cmd.add(model);
            cmd.add("--output-format"); cmd.add("json");
            cmd.add("--dangerously-skip-permissions");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().putAll(buildEnv());
            Process proc = pb.start();

            String stdout = readWithTimeout(proc, 3);
            if (stdout == null) return null;
            stdout = stdout.trim();
            if (stdout.isEmpty()) return null;

            try {
                Json json = Json.parse(stdout);
                String result = json.getString("result");
                if (result != null) return result;
            } catch (Exception ignored) {}

            return stdout.length() > 1000 ? stdout.substring(0, 1000) : stdout;
        } catch (Exception e) {
            log.warning("Session summarize failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Build a ClaudeCliProcessor if the claude CLI is reachable, else null.
     * Caller falls through to the next processor option on null.
     */
    public static MessageProcessor create(HttpClient http, PeerConfig config, OpenBrainStore brain) {
        String bin = findClaudeBin();
        if (bin == null) {
            log.warning("claude CLI not found — ClaudeCliProcessor unavailable");
            return null;
        }
        log.info("ClaudeCliProcessor active — bin=" + bin + " model=" + config.claudeModel
            + " timeout=" + config.claudeTimeoutMinutes + "m"
            + " sessionTTL=" + config.sessionTtlMinutes + "m"
            + " node=" + config.nodeName);
        return new ClaudeCliProcessor(http, config, brain, bin);
    }

    @Override
    public void process(OpenBrainStore.PendingMessage msg) throws Exception {
        // Doom-loop guard: skip Claude invocation on bare acknowledgement payloads.
        // The system prompt instructs Claude to reply "noop — ack received" for
        // ack-shaped inbound content, but those replies go out without kind=ack
        // (sendReply doesn't stamp kind), so the receiving peer treats them as
        // actions and triggers another Claude run. Detecting acks at the consumer
        // side breaks the loop regardless of the sender's header hygiene.
        // See messenger#9.
        String body = RelayHandler.extractBody(msg.content());
        if (isAcknowledgement(body)) {
            log.info("Skipping ack content — thread_id=" + msg.threadId()
                + " from=" + msg.fromNode() + " (matched ack pattern, no Claude invocation)");
            return;
        }

        log.info("Processing via claude CLI — thread_id=" + msg.threadId()
            + " from=" + msg.fromNode());

        String reply = runClaude(msg.threadId(), msg.fromNode(), msg.content());
        sendReply(msg.fromNode(), reply, msg.threadId());

        log.info("Reply sent — thread_id=" + msg.threadId() + " to=" + msg.fromNode());
    }

    // Matches bare acknowledgement payloads like "noop", "ack received",
    // "noop — ack received" (em-dash) or "noop - ack received" (hyphen).
    // Case-insensitive; leading whitespace is tolerated via the trim() call.
    static final Pattern ACK_PATTERN = Pattern.compile(
        "^(noop|ack received|noop\\s*[—-]\\s*ack(\\s+received)?)\\b",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * True when {@code body} is a bare acknowledgement that does not warrant
     * a Claude response. Package-private for testability.
     */
    static boolean isAcknowledgement(String body) {
        if (body == null) return false;
        return ACK_PATTERN.matcher(body.trim()).find();
    }

    private String runClaude(long threadId, String fromNode, String userContent) throws Exception {
        SessionEntry existing = sessions.get(threadId);

        List<String> cmd = new ArrayList<>();
        cmd.add(claudeBin);
        cmd.add("-p");

        if (existing != null) {
            // Resume existing session — Claude has full context
            cmd.add("--resume");
            cmd.add(existing.sessionId());
            cmd.add(userContent);
        } else {
            // New session — build system prompt, check for prior context in OpenBrain
            StringBuilder prompt = new StringBuilder();
            prompt.append(SystemPrompt.build(config, fromNode, threadId, true));

            String priorContext = brain.fetchSessionContext(threadId);
            if (priorContext != null) {
                prompt.append("\n\n## Previous Conversation Context\n\n").append(priorContext);
                log.info("Loaded prior session context for thread_id=" + threadId);
            }

            prompt.append("\n\n---\n\n").append(userContent);
            cmd.add(prompt.toString());
        }

        cmd.add("--model");         cmd.add(model);
        cmd.add("--output-format"); cmd.add("json");
        cmd.add("--dangerously-skip-permissions");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().putAll(buildEnv());
        pb.redirectErrorStream(false);

        Process proc = pb.start();

        String stdout = readWithTimeout(proc, timeoutMinutes);
        if (stdout == null) {
            throw new Exception("claude CLI timed out after " + timeoutMinutes + "m");
        }
        stdout = stdout.trim();

        return parseCliOutput(stdout, threadId);
    }

    /**
     * Run a subprocess with a hard timeout that actually fires.
     *
     * The obvious pattern — {@code readAllBytes()} followed by
     * {@code waitFor(timeout)} — is broken because {@code readAllBytes}
     * blocks until stdout closes. If the subprocess hangs with stdout open
     * (auth stall, upstream API wedge, tool retry loop), {@code readAllBytes}
     * never returns and the timeout check is never reached. Observed
     * in production: a claude subprocess held the poller hostage for
     * 11.5 hours because this race-free-looking code can't actually race.
     *
     * Fix: read stdout on a virtual thread so {@code waitFor(timeout)} runs
     * on the main path and can hard-kill the subprocess on timeout.
     *
     * @return trimmed stdout on clean exit, or null on timeout (subprocess killed).
     */
    private String readWithTimeout(Process proc, int minutes) throws Exception {
        var stdoutBuf = new java.io.ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> {
            try { proc.getInputStream().transferTo(stdoutBuf); }
            catch (java.io.IOException ignored) { /* stream closed on kill */ }
        });

        boolean finished = proc.waitFor(minutes, java.util.concurrent.TimeUnit.MINUTES);
        if (!finished) {
            log.warning("claude CLI exceeded " + minutes + "m — destroying subprocess");
            proc.destroyForcibly();
            // Give the reader a brief moment to see EOF after destroyForcibly, then bail
            reader.join(Duration.ofSeconds(5));
            return null;
        }

        // Process exited cleanly; drain the reader
        reader.join(Duration.ofSeconds(5));
        return stdoutBuf.toString(StandardCharsets.UTF_8);
    }

    private String parseCliOutput(String stdout, long threadId) {
        if (stdout.isEmpty()) return "[no output]";

        try {
            Json out = Json.parse(stdout);
            String sid = out.getString("session_id");
            if (sid != null) {
                sessions.put(threadId, new SessionEntry(sid, Instant.now()));
                log.fine("Session stored thread_id=" + threadId + " session=" + sid);
            }
            String result = out.getString("result");
            if (result != null) return result;
        } catch (Exception ignored) {
            // JSON parse failed — fall through to raw output
        }

        return stdout.length() > 2000 ? stdout.substring(0, 2000) + "…" : stdout;
    }

    private void sendReply(String toNode, String content, long threadId) throws Exception {
        String body = "{\"to\":\"" + toNode + "\","
            + "\"from\":\"" + config.nodeName + "\","
            + "\"content\":\"" + Json.escape(content) + "\","
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

}
