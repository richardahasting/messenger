package org.hastingtx.meshrelay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ConnectException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PeerConfig's load strategy: OpenBrain (with startup retry) → own cache → bootstrap.
 *
 * Issue #35: the fallback cache was a single file shared by every instance on a host,
 * so when OpenBrain was briefly unreachable at boot an instance could load its sibling's
 * config (port + processor) and come up as the wrong thing while /health said "ok".
 */
class PeerConfigLoadTest {

    @TempDir Path home;

    private final List<Duration> sleeps = new ArrayList<>();
    private final PeerConfig.Sleeper recordSleep = sleeps::add;

    private static final String AIR_CONFIG =
        "{\"listen_port\":13007,\"peers\":[{\"name\":\"macmini\",\"url\":\"http://192.168.0.226:13007\"}]}";
    private static final String GEMMA_CONFIG =
        "{\"listen_port\":13009,\"processor\":\"gemma\",\"peers\":[]}";

    private static final PeerConfig.ConfigFetcher OPENBRAIN_DOWN =
        (url, key, node) -> { throw new ConnectException("Connection refused"); };

    private static String bootstrap(String node) {
        return bootstrap(node, null);
    }

    private static String bootstrap(String node, Integer startupWaitSeconds) {
        return "{\"node_name\":\"" + node + "\","
            + "\"openbrain_url\":\"http://brain:3000\","
            + "\"openbrain_key\":\"k\""
            + (startupWaitSeconds != null ? ",\"openbrain_startup_wait_seconds\":" + startupWaitSeconds : "")
            + "}";
    }

    private PeerConfig load(String bootstrapJson, PeerConfig.ConfigFetcher fetcher) throws Exception {
        return PeerConfig.load(bootstrapJson, null, home, fetcher, recordSleep);
    }

    // ── Per-instance cache path ──────────────────────────────────────────────

    @Test
    void cacheFileIsDifferentForEachInstance() {
        assertNotEquals(PeerConfig.cacheFileFor(home, "macbook-air"),
                        PeerConfig.cacheFileFor(home, "gemma-large"));
    }

    @Test
    void cacheFileNameCannotEscapeTheHomeDirectory() {
        assertEquals(home, PeerConfig.cacheFileFor(home, "../../etc/passwd").getParent());
    }

    @Test
    void successfulLoadWritesThisInstancesCacheNotTheSharedFile() throws Exception {
        PeerConfig cfg = load(bootstrap("macbook-air"), (u, k, n) -> AIR_CONFIG);

        assertEquals("openbrain", cfg.source);
        assertTrue(Files.exists(PeerConfig.cacheFileFor(home, "macbook-air")));
        assertFalse(Files.exists(home.resolve(".messenger-config.cache")),
            "the legacy shared cache path must never be written again");
    }

    // ── The #35 scenario ─────────────────────────────────────────────────────

    @Test
    void siblingsOnOneHostEachFallBackToTheirOwnConfig() throws Exception {
        load(bootstrap("macbook-air"), (u, k, n) -> AIR_CONFIG);
        load(bootstrap("gemma-large"), (u, k, n) -> GEMMA_CONFIG);   // last writer used to win

        PeerConfig air = load(bootstrap("macbook-air", 0), OPENBRAIN_DOWN);

        assertEquals("cache", air.source);
        assertEquals(13007, air.listenPort, "must not take gemma-large's port");
        assertNull(air.processor, "must not take gemma-large's processor");
    }

    @Test
    void cacheRecordedForAnotherNodeIsRejected() throws Exception {
        // Simulate a cache file that holds gemma-large's config at macbook-air's path.
        load(bootstrap("gemma-large"), (u, k, n) -> GEMMA_CONFIG);
        Files.copy(PeerConfig.cacheFileFor(home, "gemma-large"),
                   PeerConfig.cacheFileFor(home, "macbook-air"));

        PeerConfig air = load(bootstrap("macbook-air", 0), OPENBRAIN_DOWN);

        assertEquals("local", air.source, "a cache for a different node must not be trusted");
        assertNull(air.processor);
    }

    @Test
    void legacyCacheWithoutIdentityIsIgnored() throws Exception {
        // Pre-#35 caches are the bare OpenBrain content: no record of which node they belong to.
        Files.writeString(home.resolve(".messenger-config.cache"), GEMMA_CONFIG);
        Files.writeString(PeerConfig.cacheFileFor(home, "macbook-air"), GEMMA_CONFIG);

        PeerConfig air = load(bootstrap("macbook-air", 0), OPENBRAIN_DOWN);

        assertEquals("local", air.source);
        assertNull(air.processor);
    }

    // ── Startup retry ────────────────────────────────────────────────────────

    @Test
    void transientFailuresAreRetriedBeforeFallingBack() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        PeerConfig.ConfigFetcher flaky = (u, k, n) -> {
            if (attempts.incrementAndGet() < 3) throw new ConnectException("Connection refused");
            return AIR_CONFIG;
        };

        PeerConfig cfg = load(bootstrap("macbook-air"), flaky);

        assertEquals("openbrain", cfg.source);
        assertEquals(3, attempts.get());
        assertEquals(2, sleeps.size());
    }

    @Test
    void rejectedRequestFallsBackWithoutRetrying() throws Exception {
        load(bootstrap("macbook-air"), (u, k, n) -> AIR_CONFIG);
        AtomicInteger attempts = new AtomicInteger();
        PeerConfig.ConfigFetcher unauthorized = (u, k, n) -> {
            attempts.incrementAndGet();
            throw new PeerConfig.ConfigRejectedException("OpenBrain returned HTTP 401");
        };

        PeerConfig cfg = load(bootstrap("macbook-air"), unauthorized);

        assertEquals("cache", cfg.source);
        assertEquals(1, attempts.get(), "a bad key won't fix itself; don't stall startup");
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void retryingStopsAtTheStartupWaitBudget() throws Exception {
        PeerConfig cfg = load(bootstrap("macbook-air", 30), OPENBRAIN_DOWN);

        Duration waited = sleeps.stream().reduce(Duration.ZERO, Duration::plus);
        assertFalse(sleeps.isEmpty());
        assertTrue(waited.compareTo(Duration.ofSeconds(30)) <= 0, "waited " + waited);
        assertEquals("local", cfg.source);
    }

    @Test
    void zeroStartupWaitMeansOneAttempt() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        load(bootstrap("macbook-air", 0), (u, k, n) -> {
            attempts.incrementAndGet();
            throw new ConnectException("Connection refused");
        });

        assertEquals(1, attempts.get());
        assertTrue(sleeps.isEmpty());
    }
}
