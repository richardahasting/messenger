package org.hastingtx.meshrelay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Version constant and the version-header stamping applied to
 * outgoing messages by RelayHandler / BroadcastHandler.
 */
class VersionTest {

    @Test
    void versionIsNeverNullOrEmpty() {
        assertNotNull(Version.VERSION);
        assertFalse(Version.VERSION.isBlank());
    }

    @Test
    void versionDefaultsToDevWhenNotBakedIn() {
        // When running from un-shaded classes (unit tests), the manifest has no
        // Implementation-Version, so the fallback kicks in. The value should be
        // "dev" during test runs and a real semver when running from the shaded JAR.
        // We just assert it's SOMETHING sensible.
        String v = Version.VERSION;
        assertTrue(v.equals("dev") || v.matches("\\d+\\.\\d+\\.\\d+.*"),
            "Expected 'dev' or semver, got: " + v);
    }

    @Test
    void stampVersionHeaderPrependsMachineParseableLine() {
        String out = RelayHandler.stampVersionHeader("hello world", "linuxserver", "1.1.0");
        assertEquals("[messenger v1.1.0 from linuxserver]\n\nhello world", out);
    }

    @Test
    void stampVersionHeaderRegexExtractableByReceiver() {
        // The receiver needs a regex that works. Verify the format matches:
        String out = RelayHandler.stampVersionHeader("any body", "macmini", "1.2.3-rc1");
        assertTrue(out.matches("(?s)^\\[messenger v(\\S+) from (\\S+)\\]\\n\\n.*"),
            "Header must match extraction regex; got: " + out);
    }

    @Test
    void stampVersionHeaderPreservesMultilineContent() {
        String body = "line one\nline two\nline three";
        String out = RelayHandler.stampVersionHeader(body, "linuxserver", "1.1.0");
        assertEquals("[messenger v1.1.0 from linuxserver]\n\nline one\nline two\nline three", out);
    }

    @Test
    void stampVersionHeaderHandlesNullVersion() {
        // Null version must not produce "vnull" — fall back to local VERSION.
        String out = RelayHandler.stampVersionHeader("body", "linuxserver", null);
        assertFalse(out.contains("vnull"), "Must not emit 'vnull' header: " + out);
        assertTrue(out.startsWith("[messenger v" + Version.VERSION + " from linuxserver]\n\n"));
    }

    @Test
    void stampVersionHeaderHandlesBlankVersion() {
        String out = RelayHandler.stampVersionHeader("body", "linuxserver", "   ");
        assertFalse(out.contains("v   "), "Must not emit whitespace version: " + out);
        assertTrue(out.startsWith("[messenger v" + Version.VERSION + " from linuxserver]\n\n"));
    }

    @Test
    void stampVersionHeaderHandlesNullFromNode() {
        String out = RelayHandler.stampVersionHeader("body", null, "1.1.0");
        assertTrue(out.startsWith("[messenger v1.1.0 from unknown]\n\n"),
            "Null fromNode should default to 'unknown'; got: " + out);
    }

    @Test
    void stampVersionHeaderHandlesNullContent() {
        // Defensive — shouldn't happen in the normal path (RelayHandler validates)
        // but we don't want a NullPointerException if it ever does.
        String out = RelayHandler.stampVersionHeader(null, "linuxserver", "1.1.0");
        assertEquals("[messenger v1.1.0 from linuxserver]\n\n", out);
    }

    @Test
    void stampVersionHeaderHandlesAllNulls() {
        String out = RelayHandler.stampVersionHeader(null, null, null);
        // Must not NPE, must produce a well-formed header with fallback values
        assertTrue(out.startsWith("[messenger v" + Version.VERSION + " from unknown]\n\n"),
            "All-null inputs must degrade gracefully: " + out);
    }

    @Test
    void stampVersionHeaderDoesNotCollapsePriorHeaders() {
        // If the body already has a header (e.g. a quoted message), we don't
        // try to deduplicate — each hop stamps its own. Last stamper wins visually.
        String alreadyStamped = "[messenger v1.0.0 from macmini]\n\nold body";
        String out = RelayHandler.stampVersionHeader(alreadyStamped, "linuxserver", "1.1.0");
        assertTrue(out.startsWith("[messenger v1.1.0 from linuxserver]\n\n"),
            "New header must be prepended, not merged");
        assertTrue(out.contains("[messenger v1.0.0 from macmini]"),
            "Prior header must be preserved inside the body");
    }
}
