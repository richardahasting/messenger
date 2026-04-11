package org.hastingtx.meshrelay;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * POST /wake — receive a wake-up notification from a peer.
 *
 * Body: {"from": "linuxserver", "thread_id": 462}
 *
 * This endpoint simply acknowledges the ping and logs it.
 * The local Claude agent queries OpenBrain independently for
 * any pending messages (status:active, to:thisNode).
 *
 * The wake-up is a hint — "check OpenBrain now" — not the message itself.
 * Even if this endpoint were never called, the agent would find pending
 * messages on its next scheduled poll.
 */
public class WakeHandler implements HttpHandler {

    private static final Logger log = Logger.getLogger(WakeHandler.class.getName());
    private final PeerConfig    config;
    private final MessagePoller poller;

    public WakeHandler(PeerConfig config, MessagePoller poller) {
        this.config = config;
        this.poller = poller;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        String body = new String(
            exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        String fromNode = RelayHandler.extractField(body, "from");
        String threadId = RelayHandler.extractField(body, "thread_id");

        // Log and trigger an immediate poll — no need to wait 10 minutes
        log.info("Wake-up received: thread_id=" + threadId + " from=" + fromNode
            + " node=" + config.nodeName);
        poller.wake();

        byte[] ack = ("{\"ack\":true,\"thread_id\":" + threadId + "}")
            .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, ack.length);
        exchange.getResponseBody().write(ack);
        exchange.close();
    }
}
