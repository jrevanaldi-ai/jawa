// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.pair;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import id.jawa.binary.BinaryNode;
import id.jawa.core.WaConstants;
import id.jawa.proto.Wa;
import id.jawa.store.AuthCreds;
import id.jawa.util.Bytes;
import id.jawa.util.Jid;
import id.jawa.util.crypto.Curve25519;
import id.jawa.util.crypto.HmacSha256;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles the QR-pairing IQ stanzas:
 * <ul>
 *   <li>{@code <iq type="set"><pair-device>...refs...</pair-device></iq>} —
 *       server sends QR refs; client formats and emits each QR string, then acks.
 *   <li>{@code <iq type="set"><pair-success>...</pair-success></iq>} —
 *       server sends the ADV identity chain after user scans; client verifies it,
 *       updates {@link AuthCreds}, and returns the {@code <pair-device-sign>} reply.
 * </ul>
 *
 * <p>Ports {@code configureSuccessfulPairing} from Baileys' {@code Utils/validate-connection.ts}.
 */
public final class PairingHandler {

    private static final Logger LOG = LoggerFactory.getLogger(PairingHandler.class);

    private final AuthCreds creds;

    public PairingHandler(AuthCreds creds) { this.creds = creds; }

    /** Build the QR strings for the {@code <pair-device>} stanza. Caller renders each. */
    public List<String> qrRefsFrom(BinaryNode pairDevice) {
        List<String> out = new ArrayList<>();
        for (BinaryNode r : pairDevice.children("ref")) {
            String ref = r.textContent();
            if (ref == null || ref.isEmpty()) continue;
            out.add(String.join(",",
                ref,
                Bytes.toBase64(creds.noiseKey.publicKey()),
                Bytes.toBase64(creds.signedIdentityKey.publicKey()),
                Bytes.toBase64(creds.advSecretKey)
            ));
        }
        return out;
    }

    /** Ack the {@code <iq type=set><pair-device>} stanza. */
    public BinaryNode ackPairDevice(String id) {
        return new BinaryNode("iq",
            Map.of("to", Jid.SERVER_WHATSAPP, "type", "result", "id", id),
            null);
    }

    /**
     * Handle {@code <iq type=set><pair-success>...</pair-success></iq>}.
     *
     * <p>Mutates {@link AuthCreds} (account, meJid, meLid, pushName, platform) and
     * returns the {@code <pair-device-sign>} IQ to send back. Caller should
     * {@link id.jawa.store.AuthStore#save save} the creds.
     */
    public BinaryNode handlePairSuccess(BinaryNode iq) {
        String id = iq.attr("id");
        BinaryNode pairSuccess = iq.child("pair-success");
        if (pairSuccess == null) throw new IllegalStateException("missing <pair-success>");

        BinaryNode deviceIdentityNode = pairSuccess.child("device-identity");
        BinaryNode platformNode       = pairSuccess.child("platform");
        BinaryNode deviceNode         = pairSuccess.child("device");
        BinaryNode bizNode            = pairSuccess.child("biz");

        if (deviceIdentityNode == null || deviceNode == null) {
            throw new IllegalStateException("missing <device-identity> or <device> in <pair-success>");
        }

        String jid = deviceNode.attr("jid");
        String lid = deviceNode.attr("lid");
        String bizName = bizNode == null ? null : bizNode.attr("name");
        String platform = platformNode == null ? null : platformNode.attr("name");

        // --- decode + verify ADV chain ---

        byte[] identityHmacBytes = deviceIdentityNode.bytesContent();
        final Wa.ADVSignedDeviceIdentityHMAC identityHmac;
        try {
            identityHmac = Wa.ADVSignedDeviceIdentityHMAC.parseFrom(identityHmacBytes);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("invalid ADVSignedDeviceIdentityHMAC", e);
        }
        byte[] details = identityHmac.getDetails().toByteArray();
        byte[] hmac    = identityHmac.getHmac().toByteArray();
        boolean hosted = identityHmac.hasAccountType()
            && identityHmac.getAccountType() == Wa.ADVEncryptionType.HOSTED;
        byte[] hmacPrefix = hosted ? WaConstants.ADV_HOSTED_ACCOUNT_SIG_PREFIX : new byte[0];

        byte[] expectedHmac = HmacSha256.sign(creds.advSecretKey, hmacPrefix, details);
        if (!constantTimeEquals(expectedHmac, hmac)) {
            throw new IllegalStateException("ADV identity HMAC mismatch — invalid pairing");
        }

        final Wa.ADVSignedDeviceIdentity account;
        try {
            account = Wa.ADVSignedDeviceIdentity.parseFrom(details);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("invalid ADVSignedDeviceIdentity", e);
        }
        byte[] accountSignatureKey = account.getAccountSignatureKey().toByteArray();
        byte[] accountSignature    = account.getAccountSignature().toByteArray();
        byte[] deviceDetails       = account.getDetails().toByteArray();

        final Wa.ADVDeviceIdentity deviceIdentity;
        try {
            deviceIdentity = Wa.ADVDeviceIdentity.parseFrom(deviceDetails);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("invalid ADVDeviceIdentity", e);
        }

        byte[] accountSignaturePrefix =
            (deviceIdentity.hasDeviceType() && deviceIdentity.getDeviceType() == Wa.ADVEncryptionType.HOSTED)
                ? WaConstants.ADV_HOSTED_ACCOUNT_SIG_PREFIX
                : WaConstants.ADV_ACCOUNT_SIG_PREFIX;

        byte[] accountMsg = Bytes.concat(
            accountSignaturePrefix, deviceDetails, creds.signedIdentityKey.publicKey()
        );
        if (!Curve25519.verify(accountSignatureKey, accountMsg, accountSignature)) {
            throw new IllegalStateException("ADV account signature invalid");
        }

        byte[] deviceMsg = Bytes.concat(
            WaConstants.ADV_DEVICE_SIG_PREFIX, deviceDetails,
            creds.signedIdentityKey.publicKey(), accountSignatureKey
        );
        byte[] deviceSignature = Curve25519.sign(creds.signedIdentityKey.privateKey(), deviceMsg);

        // Re-encode account with deviceSignature, but strip accountSignatureKey
        // (Baileys' encodeSignedDeviceIdentity with includeSignatureKey=false).
        Wa.ADVSignedDeviceIdentity accountReply = Wa.ADVSignedDeviceIdentity.newBuilder()
                .setDetails(account.getDetails())
                .setAccountSignature(account.getAccountSignature())
                .setDeviceSignature(ByteString.copyFrom(deviceSignature))
                // intentionally omit accountSignatureKey
                .build();

        // --- mutate creds + build reply ---

        creds.account = Wa.ADVSignedDeviceIdentity.newBuilder()
                .setDetails(account.getDetails())
                .setAccountSignature(account.getAccountSignature())
                .setAccountSignatureKey(ByteString.copyFrom(accountSignatureKey))
                .setDeviceSignature(ByteString.copyFrom(deviceSignature))
                .build().toByteArray();
        creds.meJid    = jid;
        creds.meLid    = lid;
        creds.pushName = bizName;
        creds.platform = platform;

        LOG.info("Paired — jid={} lid={} platform={} biz={}", jid, lid, platform, bizName);

        BinaryNode deviceIdentityReply = new BinaryNode("device-identity",
            Map.of("key-index", Integer.toString(deviceIdentity.getKeyIndex())),
            accountReply.toByteArray());
        BinaryNode pairDeviceSign = new BinaryNode("pair-device-sign",
            Map.of(), List.of(deviceIdentityReply));

        return new BinaryNode("iq",
            Map.of("to", Jid.SERVER_WHATSAPP, "type", "result", "id", id),
            List.of(pairDeviceSign));
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }
}
