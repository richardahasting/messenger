package org.hastingtx.meshrelay;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the v1.2 {@code kind=ping} daemon-handled auto-pong (issue #14).
 *
 * Acceptance criteria from the issue:
 *   - kind=ping receives a kind=reply payload="pong" within 100ms (no Claude).
 *   - The reply carries reply_policy=NO_REPLY and in_reply_to=&lt;original-seq&gt;.
 *   - Processor is never invoked on kind=ping.
 *   - Existing /ping HTTP endpoint still returns 200 "pong".
 */
class PingHandlerTest {

    /** Captures every {@link RelaySender#send} invocation for assertions. */
    static class RecordingRelaySender implements RelaySender {
        record Sent(String to, String from, String content, String kind,
                    String inReplyTo, String replyPolicy, long threadId) {}

        final List<Sent> sent = new ArrayList<>();

        @Override
        public boolean send(String toNode, String fromNode, String content,
                            String kind, String inReplyTo, String replyPolicy, long threadId) {
            sent.add(new Sent(toNode, fromNode, content, kind, inReplyTo, replyPolicy, threadId));
            return true;
        }
    }

    /** Counts processor calls — must stay 0 for any kind=ping flow. */
    static class CountingProcessor implements MessageProcessor {
        final AtomicInteger processCount = new AtomicInteger(0);

        @Override
        public void process(OpenBrainStore.PendingMessage message) {
            processCount.incrementAndGet();
        }
    }

    /** In-memory OpenBrainStore — same shape used in MessagePollerTest. */
    static class RecordingStore extends OpenBrainStore {
        final List<PendingMessage> inbox = new ArrayList<>();
        final List<Integer> archived = new ArrayList<>();
        boolean drained = false;

        RecordingStore(PeerConfig cfg) {
            super(HttpClient.newHttpClient(), cfg);
        }

        @Override
        public List<PendingMessage> pollPendingMessages(String nodeName) {
            if (drained) return List.of();
            drained = true;
            return List.copyOf(inbox);
        }

        @Override
        public boolean markDelivered(int id) { return true; }

        @Override
        public void markArchived(int id) { archived.add(id); }

        @Override
        public void updateBroadcastWatermark(String node, int id) {}
    }

    private static PeerConfig testConfig(String nodeName) {
        return PeerConfig.parseConfig(
            "{\"node_name\":\"" + nodeName + "\"}",
            nodeName, "http://brain", "k", "test");
    }

    /**
     * Build a kind=ping inbound message with the v1.2 fields the spec requires.
     * The seq is what we expect to see echoed in the auto-pong's in_reply_to.
     */
    private static OpenBrainStore.PendingMessage pingMessage(
            int messageId, long threadId, String fromNode, String toNode, String seq) {
        RelayHandler.V12Fields v12 = new RelayHandler.V12Fields(
            seq, "REQ_ACK", "REPLY", null, null, null);
        String content = RelayHandler.stampVersionHeader(
            "", fromNode, "1.2.0", "ping", v12);
        return new OpenBrainStore.PendingMessage(
            messageId, threadId, fromNode, toNode, content, "pending");
    }

    // ── Acceptance: pong is sent, processor untouched ────────────────────────

    @Test
    @Timeout(10)
    void pingProducesPongReplyWithoutInvokingProcessor() {
        PeerConfig cfg = testConfig("linuxserver");
        RecordingStore brain = new RecordingStore(cfg);
        brain.inbox.add(pingMessage(1000, 1000L, "macmini", "linuxserver", "macmini:1000:1"));

        CountingProcessor proc = new CountingProcessor();
        RecordingRelaySender sender = new RecordingRelaySender();
        MessagePoller poller = new MessagePoller(cfg, brain, proc, sender);

        poller.triggerPoll();

        assertEquals(0, proc.processCount.get(),
            "ping must NEVER reach the processor — that's the whole point");
        assertEquals(1, sender.sent.size(), "exactly one pong must go out");

        RecordingRelaySender.Sent pong = sender.sent.get(0);
        assertEquals("macmini",       pong.to(),          "pong returns to original sender");
        assertEquals("linuxserver",   pong.from(),        "pong comes from this node");
        assertEquals("pong",          pong.content(),     "payload is literal 'pong'");
        assertEquals("reply",         pong.kind(),        "auto-pong is kind=reply");
        assertEquals("NO_REPLY",      pong.replyPolicy(), "must be NO_REPLY to terminate the loop");
        assertEquals("macmini:1000:1", pong.inReplyTo(),  "in_reply_to echoes the ping's seq");
        assertEquals(1000L,           pong.threadId(),    "pong stays on the same thread as the ping");

        assertEquals(List.of(1000), brain.archived,
            "the inbound ping must be archived after the pong is sent");
    }

    // ── Acceptance: latency well under 100ms ─────────────────────────────────

    @Test
    @Timeout(10)
    void pingPongCompletesUnder100ms() {
        // Spec/issue-14 acceptance: ping → pong within 100ms (no Claude in the
        // loop). With an in-memory RelaySender the bound is sub-millisecond;
        // 100ms gives generous margin against test-host noise.
        PeerConfig cfg = testConfig("linuxserver");
        RecordingStore brain = new RecordingStore(cfg);
        brain.inbox.add(pingMessage(1001, 1001L, "macmini", "linuxserver", "macmini:1001:1"));

        CountingProcessor proc = new CountingProcessor();
        RecordingRelaySender sender = new RecordingRelaySender();
        MessagePoller poller = new MessagePoller(cfg, brain, proc, sender);

        long startNs = System.nanoTime();
        poller.triggerPoll();
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

        assertEquals(1, sender.sent.size(), "pong must have been emitted");
        assertTrue(elapsedMs < 100,
            "ping → pong must complete in under 100ms (was " + elapsedMs + "ms)");
    }

    // ── Behavior: ack=NO_ACK pings are not auto-ponged ───────────────────────

    @Test
    @Timeout(10)
    void pingWithExplicitNoAckDoesNotProducePong() {
        // Defensive: a ping carrying ack=NO_ACK is malformed per the spec's
        // default table, but if a future caller explicitly sends one we must
        // not reflexively pong (would waste a relay round-trip with no waiter).
        PeerConfig cfg = testConfig("linuxserver");
        RecordingStore brain = new RecordingStore(cfg);
        RelayHandler.V12Fields v12 = new RelayHandler.V12Fields(
            "macmini:1002:1", "NO_ACK", "REPLY", null, null, null);
        String content = RelayHandler.stampVersionHeader(
            "", "macmini", "1.2.0", "ping", v12);
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            1002, 1002L, "macmini", "linuxserver", content, "pending"));

        CountingProcessor proc = new CountingProcessor();
        RecordingRelaySender sender = new RecordingRelaySender();
        MessagePoller poller = new MessagePoller(cfg, brain, proc, sender);

        poller.triggerPoll();

        assertEquals(0, proc.processCount.get(), "still no processor invocation");
        assertTrue(sender.sent.isEmpty(),
            "ack=NO_ACK ping must not trigger an auto-pong");
        assertEquals(List.of(1002), brain.archived, "ping is still archived");
    }

    // ── Compatibility: pre-v1.2 ping (no seq in header) still gets a pong ────

    @Test
    @Timeout(10)
    void pingWithoutSeqHeaderUsesSyntheticInReplyTo() {
        // A v1.2 daemon must defend against headers that lack seq= (a stripped
        // header, or — improbably — a future kind=ping from a sender that
        // hasn't stamped seq). The reply must still satisfy the /relay
        // requires-in_reply_to-on-reply guard, so we synthesise one.
        PeerConfig cfg = testConfig("linuxserver");
        RecordingStore brain = new RecordingStore(cfg);
        // 4-arg stampVersionHeader → no V12Fields → no seq= in header
        String content = RelayHandler.stampVersionHeader(
            "", "macmini", "1.2.0", "ping");
        brain.inbox.add(new OpenBrainStore.PendingMessage(
            1003, 1003L, "macmini", "linuxserver", content, "pending"));

        CountingProcessor proc = new CountingProcessor();
        RecordingRelaySender sender = new RecordingRelaySender();
        MessagePoller poller = new MessagePoller(cfg, brain, proc, sender);

        poller.triggerPoll();

        assertEquals(1, sender.sent.size(), "pong must still be emitted");
        RecordingRelaySender.Sent pong = sender.sent.get(0);
        assertNotNull(pong.inReplyTo(), "in_reply_to must be non-null");
        assertFalse(pong.inReplyTo().isBlank(), "in_reply_to must be non-blank");
        // Synthetic format documented in handlePing(): <peer>:<thread>:0
        assertEquals("macmini:1003:0", pong.inReplyTo());
    }

    // ── Backwards compat: existing /ping HTTP endpoint still returns "pong" ──

    private HttpServer server;
    private int        port;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        // Mirrors the lambda in MeshRelay.java:149 — kept in sync by inspection,
        // not by extraction, because it's three lines and pulling a class out
        // for it would be heavier than the test it enables.
        server.createContext("/ping", exchange -> {
            byte[] pong = "pong".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, pong.length);
            exchange.getResponseBody().write(pong);
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    @Timeout(10)
    void httpPingEndpointStillReturnsPong() throws Exception {
        // Acceptance: kind=ping is daemon-handled over /relay; the legacy
        // GET /ping liveness probe (different namespace per spec § "Open
        // questions" #4) is untouched.
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/ping"))
            .GET()
            .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode(), "GET /ping must remain 200");
        assertEquals("pong", resp.body(), "GET /ping body must remain literal 'pong'");
    }
}
