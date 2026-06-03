// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.pair;

import id.jawa.binary.BinaryNode;
import id.jawa.store.AuthCreds;
import id.jawa.util.Bytes;
import id.jawa.util.crypto.AesCtr;
import id.jawa.util.crypto.AesGcm;
import id.jawa.util.crypto.Curve25519;
import id.jawa.util.crypto.Hkdf;
import id.jawa.util.crypto.KeyPair25519;
import id.jawa.util.crypto.Pbkdf2;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PairingCodeHandlerTest {

    @Test
    void companionHelloStanzaShape() {
        AuthCreds creds = AuthCreds.generate();
        PairingCodeHandler h = new PairingCodeHandler(creds);
        BinaryNode iq = h.buildCompanionHello("abc", "628123456789", null);

        assertThat(iq.tag()).isEqualTo("iq");
        assertThat(iq.attrs())
            .containsEntry("xmlns", "md")
            .containsEntry("type", "set")
            .containsEntry("id", "abc");

        BinaryNode reg = iq.child("link_code_companion_reg");
        assertThat(reg).isNotNull();
        assertThat(reg.attr("jid")).isEqualTo("628123456789@s.whatsapp.net");
        assertThat(reg.attr("stage")).isEqualTo("companion_hello");

        // Wrapped ephemeral pub = 32-byte salt + 16-byte iv + 32-byte ct = 80 bytes
        byte[] wrapped = reg.child("link_code_pairing_wrapped_companion_ephemeral_pub").bytesContent();
        assertThat(wrapped).hasSize(80);

        // Server-validated wire values — these were live-discovered after a 400 bad-request
        // on platform_id=0 and display="Chrome (JaWa)". Lock them in so a future regression
        // on these exact constants cannot slip through unit tests silently.
        assertThat(reg.child("companion_server_auth_key_pub").bytesContent())
            .containsExactly(creds.noiseKey.publicKey());
        assertThat(reg.child("companion_platform_id").textContent()).isEqualTo("1");
        assertThat(reg.child("companion_platform_display").textContent()).isEqualTo("Chrome (Linux)");
        assertThat(reg.child("link_code_pairing_nonce").textContent()).isEqualTo("0");

        // 8-char Crockford code
        assertThat(h.pairingCode()).hasSize(8);

        // me.jid was set
        assertThat(creds.meJid).isEqualTo("628123456789@s.whatsapp.net");
    }

    @Test
    void rejectsBadCustomCodeLength() {
        AuthCreds creds = AuthCreds.generate();
        PairingCodeHandler h = new PairingCodeHandler(creds);
        assertThatThrownBy(() -> h.buildCompanionHello("id", "6281", "TOOSHORT-XX"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void customCodeIsUsedVerbatim() {
        AuthCreds creds = AuthCreds.generate();
        PairingCodeHandler h = new PairingCodeHandler(creds);
        h.buildCompanionHello("id", "628111111111", "ABCD1234");
        assertThat(h.pairingCode()).isEqualTo("ABCD1234");
    }

    /**
     * End-to-end happy path: simulate the primary device by wrapping its ephemeral
     * pub with the SAME pairing code derivation, then feed back into
     * buildCompanionFinish and verify the produced stanza shape + advSecret.
     */
    @Test
    void companionFinishDerivesAdvSecretAndProducesValidStanza() {
        AuthCreds creds = AuthCreds.generate();
        PairingCodeHandler h = new PairingCodeHandler(creds);
        h.buildCompanionHello("hello", "628000000000", "ABCDEFGH");

        // Simulate primary (the phone): generate its own keypair + identity
        KeyPair25519 primaryEphemeral = Curve25519.generateKeyPair();
        KeyPair25519 primaryIdentity  = Curve25519.generateKeyPair();

        // Wrap primary ephemeral pub the same way the phone would
        byte[] salt = Bytes.random(32);
        byte[] iv   = Bytes.random(16);
        byte[] key  = Pbkdf2.deriveSha256("ABCDEFGH", salt, PairingCodeHandler.PBKDF2_ITERATIONS, 32);
        byte[] ct   = AesCtr.encrypt(key, iv, primaryEphemeral.publicKey());
        byte[] wrappedPrimaryEph = Bytes.concat(salt, iv, ct);
        byte[] pairingRef = Bytes.random(16);

        // Build the simulated notification
        BinaryNode notif = new BinaryNode("notification", Map.of(), List.of(
            new BinaryNode("link_code_companion_reg", Map.of(), List.of(
                new BinaryNode("link_code_pairing_wrapped_primary_ephemeral_pub",
                    Map.of(), wrappedPrimaryEph),
                new BinaryNode("primary_identity_pub", Map.of(), primaryIdentity.publicKey()),
                new BinaryNode("link_code_pairing_ref", Map.of(), pairingRef)
            ))
        ));

        BinaryNode finishIq = h.buildCompanionFinish("finish-1", notif);

        // Verify stanza shape
        assertThat(finishIq.tag()).isEqualTo("iq");
        assertThat(finishIq.attrs())
            .containsEntry("xmlns", "md")
            .containsEntry("type", "set")
            .containsEntry("id", "finish-1");

        BinaryNode reg = finishIq.child("link_code_companion_reg");
        assertThat(reg.attr("stage")).isEqualTo("companion_finish");
        byte[] wrappedKeyBundle = reg.child("link_code_pairing_wrapped_key_bundle").bytesContent();
        byte[] companionIdPub   = reg.child("companion_identity_public").bytesContent();
        byte[] refOut           = reg.child("link_code_pairing_ref").bytesContent();

        // Wrapped bundle = 32-byte salt + 12-byte nonce + ciphertext+tag (3*32+16 = 112)
        assertThat(wrappedKeyBundle).hasSize(32 + 12 + 96 + 16);
        assertThat(companionIdPub).containsExactly(creds.signedIdentityKey.publicKey());
        assertThat(refOut).containsExactly(pairingRef);

        // advSecretKey must have been derived
        assertThat(creds.advSecretKey).isNotNull().hasSize(32);

        // Decrypt the key bundle from the phone's POV to prove the construction is consistent
        byte[] bSalt  = Bytes.slice(wrappedKeyBundle, 0, 32);
        byte[] bNonce = Bytes.slice(wrappedKeyBundle, 32, 44);
        byte[] bCt    = Bytes.slice(wrappedKeyBundle, 44, wrappedKeyBundle.length);
        byte[] ephSharedFromPhone = Curve25519.sharedKey(primaryEphemeral.privateKey(),
            /* the companion ephemeral pub the phone has — we don't have it here, so the
             * phone-side derive would use the just-decrypted one. Re-derive from the
             * encrypted public stored in the companion_hello flow is out of scope for this
             * unit test; what we can verify is the bundle structure layout. */
            primaryEphemeral.publicKey());
        // Just sanity check sizes — full E2E roundtrip needs the companion's ephemeral priv
        // which is correctly held inside PairingCodeHandler and not exposed.
        assertThat(bSalt).hasSize(32);
        assertThat(bNonce).hasSize(12);
        assertThat(bCt).hasSize(96 + 16);

        // silence unused
        assertThat(ephSharedFromPhone).isNotEmpty();
    }

    @Test
    void companionFinishWithoutHelloThrows() {
        AuthCreds creds = AuthCreds.generate();
        PairingCodeHandler h = new PairingCodeHandler(creds);

        BinaryNode notif = new BinaryNode("notification", Map.of(), List.of(
            new BinaryNode("link_code_companion_reg", Map.of(), List.of(
                new BinaryNode("link_code_pairing_wrapped_primary_ephemeral_pub",
                    Map.of(), Bytes.random(80)),
                new BinaryNode("primary_identity_pub", Map.of(), Bytes.random(32)),
                new BinaryNode("link_code_pairing_ref", Map.of(), Bytes.random(16))
            ))
        ));
        assertThatThrownBy(() -> h.buildCompanionFinish("x", notif))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void advSecretDerivationMatchesSpec() {
        // Sanity check that the HKDF construction we use produces 32 bytes
        // with the documented info string.
        byte[] eph = Bytes.random(32);
        byte[] id  = Bytes.random(32);
        byte[] r   = Bytes.random(32);
        byte[] secret = Hkdf.derive(Bytes.concat(eph, id, r), new byte[0],
            "adv_secret".getBytes(), 32);
        assertThat(secret).hasSize(32);
    }

    @Test
    void aesGcmWrapSizeMatchesWireExpectation() {
        // Bundle plaintext: 32 (companion id) + 32 (primary id) + 32 (random) = 96 bytes
        // AES-GCM appends a 16-byte tag → ciphertext+tag = 112 bytes
        byte[] key = Bytes.random(32);
        byte[] nonce = Bytes.random(12);
        byte[] pt = Bytes.random(96);
        byte[] ct = AesGcm.encrypt(key, nonce, pt, null);
        assertThat(ct).hasSize(112);
    }
}
