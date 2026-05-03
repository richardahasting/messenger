package org.hastingtx.meshrelay;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * Production {@link RelaySender} — POSTs to the daemon's own /relay endpoint
 * over loopback. Used by {@link MessagePoller} to emit the auto-pong reply
 * when a {@code kind=ping} arrives.
 *
 * Going through /relay (rather than calling OpenBrainStore directly) keeps the
 * outbound path uniform: the same wake-up retries, the same header stamping,
 * the same v1.2 field defaulting that all other senders get for free.
 *
 * Self-loop overhead: one localhost HTTP round-trip on a virtual thread —
 * sub-millisecond on a quiet machine, well inside the 100 ms budget the spec
 * sets for ping (issue #14 acceptance criteria).
 */
public class HttpRelaySender implements RelaySender {

    private static final Logger   log     = Logger.getLogger(HttpRelaySender.class.getName());
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient client;
    private final String     relayUrl;

    public HttpRelaySender(HttpClient client, int listenPort) {
        this.client   = client;
        this.relayUrl = "http://localhost:" + listenPort + "/relay";
    }

    @Override
    public boolean send(String toNode, String fromNode, String content,
                        String kind, String inReplyTo, String replyPolicy, long threadId) {
        StringBuilder body = new StringBuilder("{");
        appendString(body, "to",      toNode);
        appendString(body, "from",    fromNode);
        appendString(body, "content", content);
        if (kind        != null) appendString(body, "kind",         kind);
        if (inReplyTo   != null) appendString(body, "in_reply_to",  inReplyTo);
        if (replyPolicy != null) appendString(body, "reply_policy", replyPolicy);
        if (threadId > 0)        body.append("\"thread_id\":").append(threadId).append(',');
        // strip trailing comma
        if (body.charAt(body.length() - 1) == ',') body.deleteCharAt(body.length() - 1);
        body.append('}');

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(relayUrl))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return true;

            log.warning("Self-relay returned HTTP " + resp.statusCode()
                + " to=" + toNode + " kind=" + kind + " body=" + resp.body());
            return false;
        } catch (Exception e) {
            log.warning("Self-relay failed to=" + toNode + " kind=" + kind
                + ": " + e.getMessage());
            return false;
        }
    }

    private static void appendString(StringBuilder sb, String key, String value) {
        sb.append('"').append(key).append("\":\"").append(Json.escape(value)).append("\",");
    }
}
