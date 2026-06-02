// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.signal;

import id.jawa.binary.BinaryNode;
import id.jawa.util.Jid;
import id.jawa.util.crypto.Curve25519;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.state.PreKeyBundle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds the {@code <iq xmlns=encrypt type=get><key>} pre-key fetch request and parses
 * the per-user, per-device pre-key bundle response.
 *
 * <p>Wire shape of the request:
 * <pre>{@code
 * <iq xmlns="encrypt" type="get" to="s.whatsapp.net" id="...">
 *   <key>
 *     <user jid="628xxx:0@s.whatsapp.net"/>
 *     <user jid="628xxx:56@s.whatsapp.net"/>
 *   </key>
 * </iq>
 * }</pre>
 *
 * <p>Response:
 * <pre>{@code
 * <iq type="result" id="...">
 *   <list>
 *     <user jid="628xxx:0@s.whatsapp.net">
 *       <registration>(regId, 4 bytes BE)</registration>
 *       <type>(KEY_BUNDLE_TYPE = 0x05)</type>
 *       <identity>(32 bytes)</identity>
 *       <key>
 *         <id>(preKeyId, 3 bytes BE)</id>
 *         <value>(pub, 32 bytes)</value>
 *       </key>
 *       <skey>
 *         <id>(signedPreKeyId, 3 bytes BE)</id>
 *         <value>(signed pub, 32 bytes)</value>
 *         <signature>(64 bytes)</signature>
 *       </skey>
 *       <device-identity>(advSignedDeviceIdentity bytes)</device-identity>
 *     </user>
 *     ...
 *   </list>
 * </iq>
 * }</pre>
 */
public final class PreKeyBundleFetcher {

    private PreKeyBundleFetcher() {}

    /** Build the multi-device pre-key fetch IQ. */
    public static BinaryNode buildFetchStanza(String iqId, List<String> deviceJids) {
        List<BinaryNode> userNodes = new ArrayList<>(deviceJids.size());
        for (String j : deviceJids) {
            userNodes.add(new BinaryNode("user", Map.of("jid", j), null));
        }
        return new BinaryNode("iq",
            Map.of(
                "xmlns", "encrypt",
                "type",  "get",
                "to",    Jid.SERVER_WHATSAPP,
                "id",    iqId
            ),
            List.of(new BinaryNode("key", Map.of(), userNodes)));
    }

    /** A parsed bundle bound to a peer's device JID. */
    public record FetchedBundle(Jid address, PreKeyBundle bundle) {}

    /**
     * Parse the {@code <iq type=result>} response. Users that returned an error or
     * are missing one-time pre-keys produce {@code null} entries that the caller
     * should filter / handle as one-time-key exhaustion.
     */
    public static List<FetchedBundle> parseResponse(BinaryNode resultIq) {
        Objects.requireNonNull(resultIq, "resultIq");
        if (!"result".equals(resultIq.attr("type"))) {
            throw new IllegalArgumentException("not a result iq: " + resultIq);
        }
        BinaryNode list = resultIq.child("list");
        if (list == null) return List.of();

        List<FetchedBundle> out = new ArrayList<>();
        for (BinaryNode user : list.children("user")) {
            FetchedBundle fb = parseUser(user);
            if (fb != null) out.add(fb);
        }
        return out;
    }

    private static FetchedBundle parseUser(BinaryNode user) {
        String jidStr = user.attr("jid");
        if (jidStr == null) return null;
        Jid jid = Jid.parse(jidStr);
        if (jid == null) return null;

        byte[] regIdBytes = bytes(user.child("registration"));
        byte[] identityBytes = bytes(user.child("identity"));
        BinaryNode keyNode = user.child("key");
        BinaryNode skeyNode = user.child("skey");
        if (regIdBytes == null || identityBytes == null || skeyNode == null) return null;

        int registrationId = decodeUintBE(regIdBytes);

        // One-time pre-key — may be absent if the server has run out
        int preKeyId = -1;
        byte[] preKeyPub = null;
        if (keyNode != null) {
            preKeyId = decodeUintBE(bytes(keyNode.child("id")));
            preKeyPub = bytes(keyNode.child("value"));
        }

        int signedPreKeyId = decodeUintBE(bytes(skeyNode.child("id")));
        byte[] signedPreKeyPub = bytes(skeyNode.child("value"));
        byte[] signedPreKeySig = bytes(skeyNode.child("signature"));
        if (signedPreKeyPub == null || signedPreKeySig == null) return null;

        try {
            IdentityKey peerIdentity = new IdentityKey(
                Curve.decodePoint(Curve25519.prependType(identityBytes), 0));
            var peerPreKey = preKeyPub == null ? null
                : Curve.decodePoint(Curve25519.prependType(preKeyPub), 0);
            var peerSignedPreKey = Curve.decodePoint(Curve25519.prependType(signedPreKeyPub), 0);

            PreKeyBundle bundle = new PreKeyBundle(
                registrationId,
                jid.device(),
                preKeyId,
                peerPreKey,
                signedPreKeyId,
                peerSignedPreKey,
                signedPreKeySig,
                peerIdentity
            );
            return new FetchedBundle(jid, bundle);
        } catch (InvalidKeyException e) {
            throw new IllegalStateException("invalid key in bundle for " + jidStr, e);
        }
    }

    private static byte[] bytes(BinaryNode n) {
        return n == null ? null : n.bytesContent();
    }

    private static int decodeUintBE(byte[] b) {
        if (b == null) return 0;
        int v = 0;
        for (byte x : b) v = (v << 8) | (x & 0xFF);
        return v;
    }
}
