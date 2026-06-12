// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.media;

import id.jawa.binary.BinaryNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Snapshot of WhatsApp's media-server connection info, fetched via
 * {@code <iq xmlns="w:m" type="set"><media_conn/></iq>}.
 *
 * <p>Cache up to {@link #expiry()}; refresh on use after that.
 */
public record MediaConn(
    String auth,
    int ttlSeconds,
    int authTtlSeconds,
    int maxBuckets,
    List<String> hosts,
    Instant fetchedAt
) {

    public Instant expiry() {
        return fetchedAt.plusSeconds(ttlSeconds);
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiry());
    }

    /** Build the IQ stanza to ask the server for fresh media-conn info. */
    public static BinaryNode buildQuery(String iqId) {
        return new BinaryNode("iq",
            Map.of(
                "id",    iqId,
                "type",  "set",
                "xmlns", "w:m",
                "to",    id.jawa.util.Jid.SERVER_WHATSAPP
            ),
            List.of(BinaryNode.of("media_conn")));
    }

    /**
     * Parse the {@code <iq type="result">} response containing the {@code <media_conn>}
     * child. Returns {@code null} if the response shape is wrong.
     */
    public static MediaConn parseResponse(BinaryNode iqResult) {
        BinaryNode mc = iqResult.child("media_conn");
        if (mc == null) return null;
        String auth = mc.attr("auth");
        if (auth == null) return null;
        int ttl = parseInt(mc.attr("ttl"), 60);
        int authTtl = parseInt(mc.attr("auth_ttl"), 30);
        int maxBuckets = parseInt(mc.attr("max_buckets"), 12);

        List<String> hosts = new ArrayList<>();
        for (BinaryNode child : mc.childrenList()) {
            if (!"host".equals(child.tag())) continue;
            String hostname = child.attr("hostname");
            if (hostname != null) hosts.add(hostname);
        }
        return new MediaConn(auth, ttl, authTtl, maxBuckets, hosts, Instant.now());
    }

    private static int parseInt(String s, int fallback) {
        if (s == null) return fallback;
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return fallback; }
    }
}
