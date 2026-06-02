// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.util.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

/**
 * AES-256-GCM with 12-byte IV and 128-bit tag appended to ciphertext.
 *
 * <p>Layout: {@code ciphertext || 16-byte-tag} — matches Node's {@code createCipheriv}
 * pattern used by Baileys and Go's {@code crypto/cipher.AEAD} pattern used by whatsmeow.
 */
public final class AesGcm {
    private static final int TAG_BITS = 128;

    private AesGcm() {}

    public static byte[] encrypt(byte[] key, byte[] iv, byte[] plaintext, byte[] aad) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            if (aad != null && aad.length > 0) c.updateAAD(aad);
            return c.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encrypt failed", e);
        }
    }

    public static byte[] decrypt(byte[] key, byte[] iv, byte[] ciphertext, byte[] aad) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            if (aad != null && aad.length > 0) c.updateAAD(aad);
            return c.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM decrypt failed", e);
        }
    }

    /** Build the 12-byte IV used during Noise: 8 zero bytes then a big-endian uint32 counter. */
    public static byte[] noiseIv(long counter) {
        byte[] iv = new byte[12];
        iv[8]  = (byte) ((counter >> 24) & 0xFF);
        iv[9]  = (byte) ((counter >> 16) & 0xFF);
        iv[10] = (byte) ((counter >> 8)  & 0xFF);
        iv[11] = (byte) ( counter        & 0xFF);
        return iv;
    }
}
