// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.util.crypto;

import id.jawa.util.Bytes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoTest {

    // ---- Curve25519 ----

    @Test
    void curve25519KeyGenProducesDistinct32ByteKeys() {
        KeyPair25519 a = Curve25519.generateKeyPair();
        KeyPair25519 b = Curve25519.generateKeyPair();
        assertThat(a.publicKey()).hasSize(32);
        assertThat(a.privateKey()).hasSize(32);
        assertThat(a.publicKey()).isNotEqualTo(b.publicKey());
    }

    @Test
    void ecdhSymmetric() {
        KeyPair25519 alice = Curve25519.generateKeyPair();
        KeyPair25519 bob   = Curve25519.generateKeyPair();
        byte[] sk1 = Curve25519.sharedKey(alice.privateKey(), bob.publicKey());
        byte[] sk2 = Curve25519.sharedKey(bob.privateKey(), alice.publicKey());
        assertThat(sk1).hasSize(32).containsExactly(sk2);
    }

    @Test
    void signAndVerifyRoundTrip() {
        KeyPair25519 kp = Curve25519.generateKeyPair();
        byte[] msg = Bytes.utf8("hello jawa");
        byte[] sig = Curve25519.sign(kp.privateKey(), msg);
        assertThat(sig).hasSize(64); // XEdDSA signatures are 64 bytes
        assertThat(Curve25519.verify(kp.publicKey(), msg, sig)).isTrue();
    }

    @Test
    void verifyFailsOnTamperedMessage() {
        KeyPair25519 kp = Curve25519.generateKeyPair();
        byte[] msg = Bytes.utf8("original");
        byte[] sig = Curve25519.sign(kp.privateKey(), msg);
        assertThat(Curve25519.verify(kp.publicKey(), Bytes.utf8("tampered"), sig)).isFalse();
    }

    @Test
    void prependAndStripTypeRoundTrip() {
        byte[] raw = Bytes.random(32);
        byte[] pre = Curve25519.prependType(raw);
        assertThat(pre).hasSize(33);
        assertThat(pre[0]).isEqualTo(Curve25519.KEY_BUNDLE_TYPE);
        assertThat(Curve25519.stripType(pre)).containsExactly(raw);
        // idempotent
        assertThat(Curve25519.prependType(pre)).containsExactly(pre);
        assertThat(Curve25519.stripType(raw)).containsExactly(raw);
    }

    // ---- HKDF (RFC 5869 test vector A.1) ----

    @Test
    void hkdfRfc5869Vector1() {
        // IKM = 0x0b * 22, salt = 0x000102...0c, info = 0xf0..f9
        byte[] ikm = new byte[22];
        for (int i = 0; i < 22; i++) ikm[i] = 0x0b;
        byte[] salt = new byte[13];
        for (int i = 0; i < 13; i++) salt[i] = (byte) i;
        byte[] info = Bytes.fromHex("f0f1f2f3f4f5f6f7f8f9");
        byte[] out = Hkdf.derive(ikm, salt, info, 42);
        assertThat(Bytes.toHex(out)).isEqualTo(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"
        );
    }

    // ---- AES-GCM ----

    @Test
    void aesGcmRoundTrip() {
        byte[] key = Bytes.random(32);
        byte[] iv  = AesGcm.noiseIv(0);
        byte[] aad = Bytes.utf8("aad");
        byte[] pt  = Bytes.utf8("hello, jawa noise!");
        byte[] ct  = AesGcm.encrypt(key, iv, pt, aad);
        assertThat(ct).hasSize(pt.length + 16); // tag appended
        assertThat(AesGcm.decrypt(key, iv, ct, aad)).containsExactly(pt);
    }

    @Test
    void aesGcmDecryptFailsOnTamperedCiphertext() {
        byte[] key = Bytes.random(32);
        byte[] iv  = AesGcm.noiseIv(7);
        byte[] ct  = AesGcm.encrypt(key, iv, Bytes.utf8("payload"), null);
        ct[ct.length - 1] ^= 0x01;
        assertThatThrownBy(() -> AesGcm.decrypt(key, iv, ct, null))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void noiseIvLayout() {
        byte[] iv0 = AesGcm.noiseIv(0);
        byte[] iv1 = AesGcm.noiseIv(1);
        byte[] ivMax = AesGcm.noiseIv(0xCAFEBABEL);
        assertThat(iv0).hasSize(12).containsExactly(0,0,0,0,0,0,0,0,0,0,0,0);
        assertThat(iv1[11]).isEqualTo((byte) 1);
        // big-endian counter at offset 8..12
        assertThat(ivMax[8]).isEqualTo((byte) 0xCA);
        assertThat(ivMax[9]).isEqualTo((byte) 0xFE);
        assertThat(ivMax[10]).isEqualTo((byte) 0xBA);
        assertThat(ivMax[11]).isEqualTo((byte) 0xBE);
    }

    // ---- HMAC ----

    @Test
    void hmacSha256KnownAnswer() {
        // RFC 4231 test 1
        byte[] key = Bytes.fromHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] mac = HmacSha256.sign(key, Bytes.utf8("Hi There"));
        assertThat(Bytes.toHex(mac)).isEqualTo(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"
        );
    }
}
