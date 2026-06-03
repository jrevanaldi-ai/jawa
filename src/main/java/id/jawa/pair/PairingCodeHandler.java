// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.pair;

import id.jawa.binary.BinaryNode;
import id.jawa.store.AuthCreds;
import id.jawa.util.Bytes;
import id.jawa.util.Crockford;
import id.jawa.util.Jid;
import id.jawa.util.crypto.AesCtr;
import id.jawa.util.crypto.AesGcm;
import id.jawa.util.crypto.Curve25519;
import id.jawa.util.crypto.Hkdf;
import id.jawa.util.crypto.KeyPair25519;
import id.jawa.util.crypto.Pbkdf2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Phone-number pairing code — the alternative to QR. Two-stage handshake:
 *
 * <ol>
 *   <li><b>companion_hello</b> ({@link #buildCompanionHello}) — client emits a random
 *     8-char Crockford code (displayed to the user), wraps its pairing-ephemeral
 *     pub with PBKDF2(code)-derived AES-CTR, and sends to the server.</li>
 *   <li><b>companion_finish</b> ({@link #buildCompanionFinish}) — when the user
 *     types the code on their phone, the server pushes a
 *     {@code <notification>} carrying the primary device's wrapped ephemeral pub
 *     and its identity pub. The client decrypts the ephemeral, derives
 *     {@code advSecret} via two X25519 agreements + HKDF, packs a key bundle
 *     (companion id || primary id || advSecretRandom) encrypted with AES-GCM,
 *     and ships it back.</li>
 * </ol>
 *
 * <p>The subsequent {@code <pair-success>} stanza is identical to the QR flow and
 * is handled by {@link PairingHandler#handlePairSuccess}.
 *
 * <p>Ports {@code requestPairingCode} (Baileys, {@code src/Socket/socket.ts}) and
 * {@code handlePairCodeNotification} (whatsmeow, {@code pair-code.go}).
 */
public final class PairingCodeHandler {

    private static final Logger LOG = LoggerFactory.getLogger(PairingCodeHandler.class);

    /** PBKDF2 iterations used for the pairing-code key derivation (Baileys + whatsmeow). */
    public static final int PBKDF2_ITERATIONS = 2 << 16; // 131_072

    /**
     * WA platform id used in the companion_hello stanza.
     * Sent as a string (nibble-packed on the wire); "1" = Chrome, "7" = Electron, etc.
     * "0" = Unknown is REJECTED by the server (bad-request 400).
     */
    public static final String COMPANION_PLATFORM_ID = "1"; // Chrome

    private final AuthCreds creds;
    private String pairingCode;
    private KeyPair25519 pairingEphemeralKey;

    public PairingCodeHandler(AuthCreds creds) { this.creds = creds; }

    /** Last code generated (8 chars). */
    public String pairingCode() { return pairingCode; }

    /**
     * Build the {@code <iq><link_code_companion_reg stage=companion_hello>} IQ.
     * Side-effects: rolls a fresh pairing-ephemeral keypair and an 8-char Crockford code,
     * and sets {@code creds.meJid} to the phone-number JID.
     */
    public BinaryNode buildCompanionHello(String iqId, String phoneNumber, String customCode) {
        if (customCode != null && customCode.length() != 8) {
            throw new IllegalArgumentException("custom pairing code must be exactly 8 chars");
        }
        pairingCode = customCode != null ? customCode : Crockford.encode(Bytes.random(5));
        pairingEphemeralKey = Curve25519.generateKeyPair();
        creds.meJid = phoneNumber + "@" + Jid.SERVER_WHATSAPP;

        byte[] wrapped = wrapEphemeralPub(pairingCode, pairingEphemeralKey.publicKey());

        BinaryNode reg = new BinaryNode("link_code_companion_reg",
            Map.of(
                "jid",   creds.meJid,
                "stage", "companion_hello",
                "should_show_push_notification", "true"
            ),
            List.of(
                new BinaryNode("link_code_pairing_wrapped_companion_ephemeral_pub", Map.of(), wrapped),
                new BinaryNode("companion_server_auth_key_pub", Map.of(), creds.noiseKey.publicKey()),
                // String content → encoder picks the optimal form (nibble for digits,
                // raw for the display text). Matches Baileys' wire output.
                new BinaryNode("companion_platform_id",      Map.of(), COMPANION_PLATFORM_ID),
                // Server validates this as "Browser (OS)" with only common values accepted —
                // arbitrary brand names ("Chrome (JaWa)") trigger bad-request 400.
                new BinaryNode("companion_platform_display", Map.of(), "Chrome (Linux)"),
                new BinaryNode("link_code_pairing_nonce",    Map.of(), "0")
            ));

        return new BinaryNode("iq",
            Map.of(
                "xmlns", "md",
                "type",  "set",
                "to",    Jid.SERVER_WHATSAPP,
                "id",    iqId
            ),
            List.of(reg));
    }

    /**
     * Handle the server's {@code <notification type=link_code_companion_reg>} push.
     * Returns the {@code <iq><link_code_companion_reg stage=companion_finish>} IQ to send back.
     *
     * <p>Side-effects: populates {@code creds.advSecretKey}.
     */
    public BinaryNode buildCompanionFinish(String iqId, BinaryNode notification) {
        BinaryNode reg = notification.child("link_code_companion_reg");
        if (reg == null) throw new IllegalStateException("missing <link_code_companion_reg> in notification");

        byte[] wrappedPrimaryEph = bytes(reg.child("link_code_pairing_wrapped_primary_ephemeral_pub"));
        byte[] primaryIdentityPub = bytes(reg.child("primary_identity_pub"));
        byte[] linkCodePairingRef = bytes(reg.child("link_code_pairing_ref"));
        if (wrappedPrimaryEph == null || wrappedPrimaryEph.length < 80) {
            throw new IllegalStateException("invalid wrapped_primary_ephemeral_pub: len="
                + (wrappedPrimaryEph == null ? -1 : wrappedPrimaryEph.length));
        }
        if (primaryIdentityPub == null || linkCodePairingRef == null) {
            throw new IllegalStateException("missing primary_identity_pub or link_code_pairing_ref");
        }
        if (pairingCode == null || pairingEphemeralKey == null) {
            throw new IllegalStateException("no pending pairing-code session — did you call buildCompanionHello?");
        }

        // Decrypt primary ephemeral pub
        byte[] primarySalt = Bytes.slice(wrappedPrimaryEph, 0, 32);
        byte[] primaryIv = Bytes.slice(wrappedPrimaryEph, 32, 48);
        byte[] primaryCt = Bytes.slice(wrappedPrimaryEph, 48, 80);
        byte[] linkKey = Pbkdf2.deriveSha256(pairingCode, primarySalt, PBKDF2_ITERATIONS, 32);
        byte[] primaryEphemeralPub = AesCtr.decrypt(linkKey, primaryIv, primaryCt);

        // ECDH x2
        byte[] ephSharedSecret = Curve25519.sharedKey(pairingEphemeralKey.privateKey(), primaryEphemeralPub);
        byte[] identitySharedKey = Curve25519.sharedKey(creds.signedIdentityKey.privateKey(), primaryIdentityPub);

        byte[] advSecretRandom = Bytes.random(32);
        byte[] advInput = Bytes.concat(ephSharedSecret, identitySharedKey, advSecretRandom);
        byte[] advSecret = Hkdf.derive(advInput, new byte[0], "adv_secret".getBytes(), 32);
        creds.advSecretKey = advSecret;

        // Build encrypted key bundle
        byte[] keyBundleSalt = Bytes.random(32);
        byte[] keyBundleNonce = Bytes.random(12);
        byte[] keyBundleEncKey = Hkdf.derive(ephSharedSecret, keyBundleSalt,
            "link_code_pairing_key_bundle_encryption_key".getBytes(), 32);
        byte[] plaintextBundle = Bytes.concat(
            creds.signedIdentityKey.publicKey(),
            primaryIdentityPub,
            advSecretRandom
        );
        byte[] encryptedBundle = AesGcm.encrypt(keyBundleEncKey, keyBundleNonce, plaintextBundle, null);
        byte[] wrappedKeyBundle = Bytes.concat(keyBundleSalt, keyBundleNonce, encryptedBundle);

        BinaryNode finish = new BinaryNode("link_code_companion_reg",
            Map.of(
                "jid",   creds.meJid,
                "stage", "companion_finish"
            ),
            List.of(
                new BinaryNode("link_code_pairing_wrapped_key_bundle", Map.of(), wrappedKeyBundle),
                new BinaryNode("companion_identity_public",            Map.of(), creds.signedIdentityKey.publicKey()),
                new BinaryNode("link_code_pairing_ref",                Map.of(), linkCodePairingRef)
            ));

        LOG.info("Built companion_finish; advSecret derived ({}B)", advSecret.length);

        return new BinaryNode("iq",
            Map.of(
                "xmlns", "md",
                "type",  "set",
                "to",    Jid.SERVER_WHATSAPP,
                "id",    iqId
            ),
            List.of(finish));
    }

    /** Wrap our companion ephemeral pub with PBKDF2-AES-CTR for transmission. */
    private static byte[] wrapEphemeralPub(String code, byte[] companionEphemeralPub) {
        byte[] salt = Bytes.random(32);
        byte[] iv = Bytes.random(16);
        byte[] key = Pbkdf2.deriveSha256(code, salt, PBKDF2_ITERATIONS, 32);
        byte[] ct = AesCtr.encrypt(key, iv, companionEphemeralPub);
        return Bytes.concat(salt, iv, ct);
    }

    private static byte[] bytes(BinaryNode n) { return n == null ? null : n.bytesContent(); }
}
