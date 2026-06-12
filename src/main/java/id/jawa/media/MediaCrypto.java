// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.media;

import id.jawa.util.crypto.AesCbc;
import id.jawa.util.crypto.Hkdf;
import id.jawa.util.crypto.HmacSha256;
import id.jawa.util.crypto.Sha256;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * E2E encryption for WhatsApp media (images, video, audio, documents).
 *
 * <p>Per upload, a fresh 32-byte random {@code mediaKey} is generated. It is expanded
 * via HKDF-SHA256 into four 16/32/32/32-byte slices used as the AES-CBC IV, the
 * AES-CBC cipher key, the HMAC-SHA256 MAC key, and an unused reference key. The
 * plaintext is AES-CBC encrypted, then HMAC(iv || ciphertext) is computed; the
 * first 10 bytes of the MAC are appended to the ciphertext.
 *
 * <p>The recipient downloads {@code ciphertext || mac10}, recomputes the MAC, then
 * runs AES-CBC decrypt with the same expanded keys. The {@code mediaKey} itself
 * rides inside the encrypted Signal {@code Wa.Message} envelope.
 *
 * <p>Per-type {@link MediaType} info string seeds the HKDF expansion so a key
 * derived for an image cannot decrypt an audio payload by mistake.
 */
public final class MediaCrypto {

    private MediaCrypto() {}

    /** Per-media-type HKDF info strings WhatsApp uses to derive keys. */
    public enum MediaType {
        IMAGE("WhatsApp Image Keys"),
        VIDEO("WhatsApp Video Keys"),
        AUDIO("WhatsApp Audio Keys"),
        DOCUMENT("WhatsApp Document Keys");

        private final byte[] info;

        MediaType(String info) {
            this.info = info.getBytes(StandardCharsets.UTF_8);
        }

        public byte[] info() { return info.clone(); }
    }

    /** Result of {@link #expandKey} — four sub-keys derived from the 32-byte media key. */
    public record ExpandedKey(byte[] iv, byte[] cipherKey, byte[] macKey, byte[] refKey) {}

    /**
     * HKDF-SHA256 expand the 32-byte {@code mediaKey} into 112 bytes, then split:
     * iv (16), cipherKey (32), macKey (32), refKey (32).
     */
    public static ExpandedKey expandKey(byte[] mediaKey, MediaType type) {
        if (mediaKey.length != 32) {
            throw new IllegalArgumentException("mediaKey must be 32 bytes, got " + mediaKey.length);
        }
        byte[] expanded = Hkdf.derive(mediaKey, new byte[0], type.info(), 112);
        return new ExpandedKey(
            Arrays.copyOfRange(expanded, 0, 16),
            Arrays.copyOfRange(expanded, 16, 48),
            Arrays.copyOfRange(expanded, 48, 80),
            Arrays.copyOfRange(expanded, 80, 112)
        );
    }

    /** Result of {@link #encrypt} — what the {@code Wa.Message.imageMessage} (or sibling) needs. */
    public record EncryptedMedia(
        byte[] ciphertext,    // AES-CBC ciphertext (no MAC)
        byte[] mac,           // 10-byte truncated HMAC over iv || ciphertext
        byte[] fileSha256,    // SHA-256 of the plaintext (Wa.Message field)
        byte[] fileEncSha256  // SHA-256 of (ciphertext || mac) — the bytes uploaded
    ) {
        /** The bytes actually uploaded to WhatsApp's media server: ciphertext || mac. */
        public byte[] uploadBytes() {
            byte[] out = new byte[ciphertext.length + mac.length];
            System.arraycopy(ciphertext, 0, out, 0, ciphertext.length);
            System.arraycopy(mac, 0, out, ciphertext.length, mac.length);
            return out;
        }
    }

    /**
     * Encrypt {@code plaintext} for upload.
     *
     * @param plaintext  the raw media bytes (decoded image/video/audio file)
     * @param mediaKey   32 random bytes — caller's responsibility to provide (e.g.
     *                   {@code Bytes.random(32)})
     * @param type       per-type HKDF info
     */
    public static EncryptedMedia encrypt(byte[] plaintext, byte[] mediaKey, MediaType type) {
        ExpandedKey k = expandKey(mediaKey, type);
        byte[] ciphertext = AesCbc.encrypt(k.cipherKey(), k.iv(), plaintext);

        byte[] macInput = new byte[k.iv().length + ciphertext.length];
        System.arraycopy(k.iv(), 0, macInput, 0, k.iv().length);
        System.arraycopy(ciphertext, 0, macInput, k.iv().length, ciphertext.length);
        byte[] fullMac = HmacSha256.sign(k.macKey(), macInput);
        byte[] mac10 = Arrays.copyOfRange(fullMac, 0, 10);

        byte[] uploadBytes = new byte[ciphertext.length + 10];
        System.arraycopy(ciphertext, 0, uploadBytes, 0, ciphertext.length);
        System.arraycopy(mac10, 0, uploadBytes, ciphertext.length, 10);

        return new EncryptedMedia(
            ciphertext,
            mac10,
            Sha256.hash(plaintext),
            Sha256.hash(uploadBytes)
        );
    }

    /**
     * Decrypt a downloaded {@code ciphertextAndMac} (the bytes from the media URL),
     * verifying the MAC before AES-CBC decrypting.
     *
     * @throws IllegalStateException if MAC verification fails
     */
    public static byte[] decrypt(byte[] ciphertextAndMac, byte[] mediaKey, MediaType type) {
        if (ciphertextAndMac.length < 10) {
            throw new IllegalArgumentException("payload too short for MAC");
        }
        ExpandedKey k = expandKey(mediaKey, type);

        int ctLen = ciphertextAndMac.length - 10;
        byte[] ciphertext = Arrays.copyOfRange(ciphertextAndMac, 0, ctLen);
        byte[] receivedMac = Arrays.copyOfRange(ciphertextAndMac, ctLen, ctLen + 10);

        byte[] macInput = new byte[k.iv().length + ciphertext.length];
        System.arraycopy(k.iv(), 0, macInput, 0, k.iv().length);
        System.arraycopy(ciphertext, 0, macInput, k.iv().length, ciphertext.length);
        byte[] fullMac = HmacSha256.sign(k.macKey(), macInput);
        byte[] expectedMac = Arrays.copyOfRange(fullMac, 0, 10);

        if (!constantTimeEquals(expectedMac, receivedMac)) {
            throw new IllegalStateException("media MAC mismatch");
        }
        return AesCbc.decrypt(k.cipherKey(), k.iv(), ciphertext);
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }
}
