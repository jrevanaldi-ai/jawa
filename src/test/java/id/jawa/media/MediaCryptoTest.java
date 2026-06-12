// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.media;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaCryptoTest {

    @Test
    void expandKeyProducesCorrectSliceSizes() {
        byte[] mediaKey = bytes(0x01, 32);
        var k = MediaCrypto.expandKey(mediaKey, MediaCrypto.MediaType.IMAGE);

        assertThat(k.iv()).hasSize(16);
        assertThat(k.cipherKey()).hasSize(32);
        assertThat(k.macKey()).hasSize(32);
        assertThat(k.refKey()).hasSize(32);
    }

    @Test
    void expandKeyIsDistinctPerType() {
        byte[] mediaKey = bytes(0x42, 32);
        var image = MediaCrypto.expandKey(mediaKey, MediaCrypto.MediaType.IMAGE);
        var audio = MediaCrypto.expandKey(mediaKey, MediaCrypto.MediaType.AUDIO);

        assertThat(image.iv()).isNotEqualTo(audio.iv());
        assertThat(image.cipherKey()).isNotEqualTo(audio.cipherKey());
    }

    @Test
    void encryptDecryptRoundTrip() {
        byte[] plaintext = "WhatsApp says hi from JaWa".getBytes();
        byte[] mediaKey = new byte[32];
        new SecureRandom().nextBytes(mediaKey);

        var enc = MediaCrypto.encrypt(plaintext, mediaKey, MediaCrypto.MediaType.DOCUMENT);
        byte[] decrypted = MediaCrypto.decrypt(enc.uploadBytes(), mediaKey, MediaCrypto.MediaType.DOCUMENT);

        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void encryptProducesShortMac() {
        var enc = MediaCrypto.encrypt(new byte[100], bytes(0x07, 32), MediaCrypto.MediaType.IMAGE);
        assertThat(enc.mac()).hasSize(10);
    }

    @Test
    void encryptUploadBytesLengthIsCiphertextPlusMac() {
        var enc = MediaCrypto.encrypt(new byte[100], bytes(0x07, 32), MediaCrypto.MediaType.IMAGE);
        assertThat(enc.uploadBytes()).hasSize(enc.ciphertext().length + 10);
    }

    @Test
    void encryptFileSha256MatchesPlaintextDigest() {
        byte[] plaintext = "the quick brown fox jumps over the lazy dog".getBytes();
        var enc = MediaCrypto.encrypt(plaintext, bytes(0x11, 32), MediaCrypto.MediaType.VIDEO);
        assertThat(enc.fileSha256()).hasSize(32);
        // fileSha256 must be over the PLAINTEXT, not the ciphertext
        byte[] manual = id.jawa.util.crypto.Sha256.hash(plaintext);
        assertThat(enc.fileSha256()).isEqualTo(manual);
        assertThat(enc.fileSha256()).isNotEqualTo(enc.fileEncSha256());
    }

    @Test
    void decryptDetectsTamperedCiphertext() {
        byte[] plaintext = "WhatsApp says hi from JaWa".getBytes();
        byte[] mediaKey = bytes(0x55, 32);
        var enc = MediaCrypto.encrypt(plaintext, mediaKey, MediaCrypto.MediaType.AUDIO);

        byte[] tampered = enc.uploadBytes().clone();
        tampered[0] ^= 0x01;

        assertThatThrownBy(() ->
            MediaCrypto.decrypt(tampered, mediaKey, MediaCrypto.MediaType.AUDIO))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("MAC mismatch");
    }

    @Test
    void decryptRejectsWrongMediaType() {
        byte[] plaintext = "secret bytes".getBytes();
        byte[] mediaKey = bytes(0x33, 32);
        var enc = MediaCrypto.encrypt(plaintext, mediaKey, MediaCrypto.MediaType.IMAGE);

        assertThatThrownBy(() ->
            MediaCrypto.decrypt(enc.uploadBytes(), mediaKey, MediaCrypto.MediaType.VIDEO))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("MAC mismatch");
    }

    @Test
    void expandKeyRejectsWrongKeyLength() {
        assertThatThrownBy(() ->
            MediaCrypto.expandKey(new byte[16], MediaCrypto.MediaType.IMAGE))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] bytes(int value, int length) {
        byte[] out = new byte[length];
        java.util.Arrays.fill(out, (byte) value);
        return out;
    }
}
