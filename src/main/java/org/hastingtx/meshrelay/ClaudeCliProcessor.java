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

    private final HttpClient             http;
    private final PeerConfig             config;
    private final OpenBrainStore         brain;
    private final String                 claudeBin;
    private final String                 relayUrl;
    private final DedupCache             dedupCache;
    private final ProgressBeatScheduler  progressBeatScheduler;

    /**
     * Daemon-managed directory where per-thread progress logs live. Created
     * by systemd via {@code RuntimeDirectory=messenger/progress} on Linux
     * boxes; missing on dev machines and in unit tests, in which case the
     * progress-beat path is silently skipped.
     */
    static final Path PROGRESS_DIR = Path.of("/var/run/messenger/progress");

    private ClaudeCliProcessor(HttpClient http, PeerConfig config,
                                OpenBrainStore brain, String claudeBin,
                                DedupCache dedupCache,
                                ProgressBeatScheduler progressBeatScheduler) {
        this.http                  = http;
        this.config                = config;
        this.brain                 = brain;
        this.claudeBin             = claudeBin;
        this.relayUrl              = "http://localhost:" + config.listenPort + "/relay";
        this.model                 = config.claudeModel;
        this.timeoutMinutes        = config.claudeTimeoutMinutes;
        this.sessionTtlMinutes     = config.sessionTtlMinutes;
        this.reaperIntervalMinutes = config.reaperIntervalMinutes;
        this.dedupCache            = dedupCache;
        this.progressBeatScheduler = progressBeatScheduler;
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
     *
     * <p>{@code dedupCache} may be null (legacy callers); when non-null the
     * processor records each outbound reply in the cache so a duplicate
     * inbound (#16) replays the response without re-invoking Claude.
     */
    public static MessageProcessor create(HttpClient http, PeerConfig config,
                                          OpenBrainStore brain, DedupCache dedupCache,
                                          ProgressBeatScheduler progressBeatScheduler) {
        String bin = findClaudeBin();
        if (bin == null) {
            log.warning("claude CLI not found — ClaudeCliProcessor unavailable");
            return null;
        }
        log.info("ClaudeCliProcessor active — bin=" + bin + " model=" + config.claudeModel
            + " timeout=" + config.claudeTimeoutMinutes + "m"
            + " sessionTTL=" + config.sessionTtlMinutes + "m"
            + " node=" + config.nodeName);
        return new ClaudeCliProcessor(http, config, brain, bin, dedupCache, progressBeatScheduler);
    }

    /** Backwards-compatible factory — no progress-beat wiring (used in tests). */
    public static MessageProcessor create(HttpClient http, PeerConfig config,
                                          OpenBrainStore brain, DedupCache dedupCache) {
        return create(http, config, brain, dedupCache, null);
    }

    /** Backwards-compatible factory — no dedup cache wiring (used in tests). */
    public static MessageProcessor create(HttpClient http, PeerConfig config, OpenBrainStore brain) {
        return create(http, config, brain, null, null);
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

        // v1.2 progress beats (issue #17). When the sender sets
        // update_interval_seconds, set up a per-thread log file and schedule
        // the heartbeat. The Claude CLI subprocess writes via the
        // MESSENGER_PROGRESS_LOG env var; this scheduler tails that file on a
        // clamped interval. try/finally guarantees the log is removed and the
        // scheduler stopped on every exit path (success, failure, timeout).
        // See docs/protocol-v1.2.md § "Progress mechanism".
        String inboundSeq = RelayHandler.extractHeaderField(msg.content(), "seq", null);
        Integer updateInterval = parseUpdateInterval(msg.content());
        Path logPath = setupProgressLog(msg.threadId(), inboundSeq,
            msg.fromNode(), updateInterval);

        String reply;
        try {
            reply = runClaude(msg.threadId(), msg.fromNode(), msg.content(), logPath);
        } finally {
            tearDownProgressLog(msg.threadId(), inboundSeq, logPath);
        }

        // Symmetric output guard (v1.1.6): suppress ack-shaped replies at the
        // source. The system prompt at SystemPrompt.java:110 instructs Claude
        // to emit "noop — ack received" for ack-shaped inputs, but peers like
        // gemma-small respond with variants ("ACK received.", "No operation
        // acknowledgment received.") that the v1.1.5 input guard does not
        // catch. Suppressing Claude's reflexive ack on the way out ends the
        // cascade regardless of what the peer emits. See messenger#9.
        if (isAcknowledgement(reply)) {
            log.info("Skipping ack-shaped reply — thread_id=" + msg.threadId()
                + " to=" + msg.fromNode() + " (matched ack pattern, not sent)");
            return;
        }

        // Auto-response stamping (issue #15). Per docs/protocol-v1.2.md
        // § "Receiver behavior" step 4: the reply MUST carry kind=reply,
        // reply_policy=NO_REPLY, and in_reply_to=<inbound seq_id>. NO_REPLY
        // is what closes the loop — without it, a peer could auto-respond
        // to our reply and we'd echo back forever.
        String inReplyTo = RelayHandler.extractHeaderField(msg.content(), "seq", null);
        if (inReplyTo == null || inReplyTo.isBlank()) {
            // Pre-v1.2 sender, or stripped header — synthesise so the reply's
            // mandatory in_reply_to is non-empty (RelayHandler 400-guards on
            // missing in_reply_to for kind=reply).
            inReplyTo = msg.fromNode() + ":" + msg.threadId() + ":0";
        }

        sendReply(msg.fromNode(), reply, msg.threadId(), inReplyTo);

        log.info("Reply sent — thread_id=" + msg.threadId() + " to=" + msg.fromNode()
            + " in_reply_to=" + inReplyTo);

        // v1.2 dedup cache (issue #16). Record the actual outbound reply so a
        // duplicate inbound replays this response instead of re-invoking
        // Claude. Cache key uses the original inbound seq from the header (not
        // the synthesised fallback), so the dedup-check on the next inbound
        // matches by triple. Skip silently if the inbound had no seq (pre-v1.2
        // sender) or no cache was wired.
        String originalSeq = RelayHandler.extractHeaderField(msg.content(), "seq", null);
        if (dedupCache != null && originalSeq != null && !originalSeq.isBlank()) {
            dedupCache.put(
                new DedupCache.DedupKey(msg.fromNode(), msg.threadId(), originalSeq),
                new DedupCache.CachedResponse("reply", reply, inReplyTo, Instant.now()));
        }
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
        return runClaude(threadId, fromNode, userContent, null);
    }

    private String runClaude(long threadId, String fromNode, String userContent,
                              Path progressLog) throws Exception {
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
        if (progressLog != null) {
            // v1.2 progress beat (#17): the system prompt tells Claude to pipe
            // long-running Bash through `tee -a $MESSENGER_PROGRESS_LOG`; the
            // PostToolUse hook (#7) appends one line per non-Bash tool call.
            // The daemon-side scheduler tails this file on its clamped cadence.
            pb.environment().put("MESSENGER_PROGRESS_LOG", progressLog.toString());
        }
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

    private void sendReply(String toNode, String content, long threadId, String inReplyTo) throws Exception {
        // v1.2 auto-response shape: kind=reply, reply_policy=NO_REPLY,
        // in_reply_to=<inbound seq>. RelayHandler defaults reply_policy to
        // NO_REPLY for kind=reply already, but stamping it explicitly guards
        // against future default changes drifting from the spec invariant
        // ("an auto-response always carries reply_policy=NO_REPLY").
        String body = "{\"to\":\"" + toNode + "\","
            + "\"from\":\"" + config.nodeName + "\","
            + "\"content\":\"" + Json.escape(content) + "\","
            + "\"kind\":\"reply\","
            + "\"reply_policy\":\"NO_REPLY\","
            + "\"in_reply_to\":\"" + Json.escape(inReplyTo) + "\","
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

    /**
     * Parse {@code update_interval_seconds} from a stamped inbound header,
     * returning null when the field is absent or malformed. Package-private
     * for testing.
     */
    static Integer parseUpdateInterval(String content) {
        String s = RelayHandler.extractHeaderField(content, "update", null);
        if (s == null || s.isBlank()) return null;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Compute the per-thread progress log path. Package-private for testing.
     */
    static Path progressLogPath(long threadId, String seqId) {
        String safeSeq = seqId == null ? "0" : seqId.replace('/', '_');
        return PROGRESS_DIR.resolve("thread-" + threadId + "-seq-" + safeSeq + ".log");
    }

    /**
     * Create the per-thread progress log and start the heartbeat. Returns the
     * log path on success, null when progress beats are not active for this
     * message (no scheduler wired, no update_interval, no seq, or the
     * runtime directory is missing — the last case is normal in dev/test).
     */
    private Path setupProgressLog(long threadId, String inboundSeq,
                                   String originalSender, Integer updateInterval) {
        if (progressBeatScheduler == null) return null;
        if (updateInterval == null)        return null;
        if (inboundSeq == null || inboundSeq.isBlank()) return null;
        if (!Files.isDirectory(PROGRESS_DIR)) {
            log.warning("Progress runtime directory missing: " + PROGRESS_DIR
                + " — skipping progress beats for thread_id=" + threadId);
            return null;
        }
        Path logPath = progressLogPath(threadId, inboundSeq);
        try {
            Files.deleteIfExists(logPath);
            Files.createFile(logPath);
        } catch (IOException e) {
            log.warning("Could not create progress log " + logPath + ": " + e.getMessage()
                + " — skipping progress beats");
            return null;
        }
        progressBeatScheduler.start(threadId, inboundSeq, originalSender, updateInterval, logPath);
        log.info("Progress beats scheduled — thread_id=" + threadId
            + " seq=" + inboundSeq + " interval=" + updateInterval + "s log=" + logPath);
        return logPath;
    }

    /**
     * Cancel the heartbeat and remove the per-thread log. Idempotent — safe
     * to call from a finally block whether or not the setup succeeded.
     */
    private void tearDownProgressLog(long threadId, String inboundSeq, Path logPath) {
        if (logPath == null || progressBeatScheduler == null) return;
        try {
            progressBeatScheduler.stop(threadId, inboundSeq);
        } catch (Exception e) {
            log.warning("Error stopping progress beat thread_id=" + threadId + ": " + e.getMessage());
        }
        try {
            Files.deleteIfExists(logPath);
        } catch (IOException e) {
            log.warning("Could not delete progress log " + logPath + ": " + e.getMessage());
        }
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
