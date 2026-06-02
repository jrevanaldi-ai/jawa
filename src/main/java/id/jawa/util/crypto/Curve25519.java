// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.util.crypto;

import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.ecc.ECPrivateKey;
import org.whispersystems.libsignal.ecc.ECPublicKey;

/**
 * Curve25519 + XEdDSA wrapper over libsignal.
 *
 * <p>All public-key bytes exposed by this class are <b>32 raw bytes</b> — the leading
 * {@code 0x05 (KEY_BUNDLE_TYPE)} byte used internally by libsignal is added/stripped here
 * so the rest of JaWa can treat keys as raw Curve25519 points (which is what
 * Noise, WhatsApp's wire format, and ADV identity use).
 */
public final class Curve25519 {

    /** Signal's "key bundle type" byte (Curve25519 / DJB). */
    public static final byte KEY_BUNDLE_TYPE = 0x05;

    private Curve25519() {}

    public static KeyPair25519 generateKeyPair() {
        ECKeyPair kp = Curve.generateKeyPair();
        byte[] pub33 = kp.getPublicKey().serialize(); // 33 bytes incl. type
        byte[] pub32 = stripType(pub33);
        byte[] priv = kp.getPrivateKey().serialize(); // 32 bytes
        return new KeyPair25519(priv, pub32);
    }

    /** X25519 ECDH. */
    public static byte[] sharedKey(byte[] privateKey, byte[] publicKey) {
        try {
            ECPublicKey pub = Curve.decodePoint(prependType(publicKey), 0);
            ECPrivateKey priv = Curve.decodePrivatePoint(privateKey);
            return Curve.calculateAgreement(pub, priv);
        } catch (InvalidKeyException e) {
            throw new IllegalArgumentException("invalid public key", e);
        }
    }

    /** XEdDSA signature with a Curve25519 private key. */
    public static byte[] sign(byte[] privateKey, byte[] message) {
        try {
            ECPrivateKey priv = Curve.decodePrivatePoint(privateKey);
            return Curve.calculateSignature(priv, message);
        } catch (InvalidKeyException e) {
            throw new IllegalArgumentException("invalid private key", e);
        }
    }

    /** XEdDSA verify. {@code publicKey} may be either 32 raw or 33 prefixed bytes. */
    public static boolean verify(byte[] publicKey, byte[] message, byte[] signature) {
        try {
            byte[] keyBytes = publicKey.length == 32 ? prependType(publicKey) : publicKey;
            ECPublicKey pub = Curve.decodePoint(keyBytes, 0);
            return Curve.verifySignature(pub, message, signature);
        } catch (InvalidKeyException e) {
            return false;
        }
    }

    /** Prepend the Signal {@code 0x05} type byte to a raw 32-byte public key. */
    public static byte[] prependType(byte[] raw32) {
        if (raw32.length == 33 && raw32[0] == KEY_BUNDLE_TYPE) return raw32;
        if (raw32.length != 32) {
            throw new IllegalArgumentException("expected 32 bytes, got " + raw32.length);
        }
        byte[] out = new byte[33];
        out[0] = KEY_BUNDLE_TYPE;
        System.arraycopy(raw32, 0, out, 1, 32);
        return out;
    }

    /** Strip the Signal {@code 0x05} type byte. No-op if already 32 bytes. */
    public static byte[] stripType(byte[] prefixed33) {
        if (prefixed33.length == 32) return prefixed33;
        if (prefixed33.length != 33) {
            throw new IllegalArgumentException("expected 33 bytes, got " + prefixed33.length);
        }
        byte[] out = new byte[32];
        System.arraycopy(prefixed33, 1, out, 0, 32);
        return out;
    }
}
