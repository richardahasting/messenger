package org.hastingtx.meshrelay;

import java.util.regex.Pattern;

/**
 * Best-effort credential redaction for {@code kind=progress} payloads (issue #17).
 *
 * <p>Defense-in-depth — not a substitute for the system-prompt rule "don't log
 * secrets", but a guard against accidental leakage when the processor's tool
 * output slips through. Replaces the value-side of each pattern with the
 * literal string {@code <redacted>}.
 *
 * <p>Patterns (all case-insensitive, illustrative — see
 * docs/protocol-v1.2.md § "Open questions" #3):
 * <ul>
 *   <li>{@code [A-Z0-9_]*(TOKEN|KEY|SECRET|PASSWORD)\s*=\s*\S+}
 *       — environment-style key/value lines.</li>
 *   <li>{@code Authorization:\s*\S+} — HTTP authorization header.</li>
 *   <li>{@code Bearer\s+\S+} — bearer-token header value.</li>
 *   <li>40-char hex string preceded by whitespace — heuristic for API keys.</li>
 * </ul>
 *
 * <p>Application order matters: the bearer pattern runs before the
 * {@code Authorization:} pattern so that {@code Authorization: Bearer xyz}
 * has both halves redacted ({@code xyz} via Bearer, then the {@code Bearer}
 * literal via Authorization).
 */
public final class SecretsSweeper {

    static final Pattern KV_PATTERN = Pattern.compile(
        "([A-Z0-9_]*(?:TOKEN|KEY|SECRET|PASSWORD))(\\s*=\\s*)\\S+",
        Pattern.CASE_INSENSITIVE);

    static final Pattern BEARER_PATTERN = Pattern.compile(
        "(Bearer\\s+)\\S+",
        Pattern.CASE_INSENSITIVE);

    static final Pattern AUTHORIZATION_PATTERN = Pattern.compile(
        "(Authorization:\\s*)\\S+",
        Pattern.CASE_INSENSITIVE);

    static final Pattern HEX_PATTERN = Pattern.compile(
        "(?<=\\s)[a-fA-F0-9]{40}(?![a-fA-F0-9])");

    private SecretsSweeper() {}

    /**
     * Redact credential-shaped substrings. Returns input unchanged when null
     * or empty; otherwise applies all four patterns and returns the rewritten
     * string. Idempotent — running redact() twice on the same input yields
     * the same output.
     */
    public static String redact(String input) {
        if (input == null || input.isEmpty()) return input;
        String s = input;
        s = KV_PATTERN.matcher(s).replaceAll("$1$2<redacted>");
        s = BEARER_PATTERN.matcher(s).replaceAll("$1<redacted>");
        s = AUTHORIZATION_PATTERN.matcher(s).replaceAll("$1<redacted>");
        s = HEX_PATTERN.matcher(s).replaceAll("<redacted>");
        return s;
    }
}
