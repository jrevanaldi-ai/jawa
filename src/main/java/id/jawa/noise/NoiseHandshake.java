// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.noise;

import id.jawa.util.Bytes;
import id.jawa.util.crypto.AesGcm;
import id.jawa.util.crypto.Curve25519;
import id.jawa.util.crypto.Hkdf;
import id.jawa.util.crypto.KeyPair25519;
import id.jawa.util.crypto.Sha256;

import java.util.Arrays;

/**
 * Noise_XX_25519_AESGCM_SHA256 handshake state.
 *
 * <p>Ports {@code Baileys/src/Utils/noise-handler.ts} (makeNoiseHandler) and the
 * {@code socket/noisehandshake.go} state struct from whatsmeow.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link #NoiseHandshake(KeyPair25519, byte[])} — initialise with the client's
 *     ephemeral keypair and the WA intro header (already mixHash-mixed during construction).
 *   <li>Send {@code HandshakeMessage{clientHello{ephemeral=clientEphPub}}} on the wire.
 *   <li>Receive {@code HandshakeMessage{serverHello}} and call
 *     {@link #processServerHello(byte[], byte[], byte[], KeyPair25519)} which returns
 *     the encrypted client static pub (for {@code clientFinish.static}).
 *   <li>Call {@link #encrypt(byte[])} to seal the {@code ClientPayload} bytes for
 *     {@code clientFinish.payload}.
 *   <li>Call {@link #finish()} which derives the two transport keys.
 * </ol>
 */
public final class NoiseHandshake {

    private final byte[] ephemeralPriv;

    private byte[] hash;
    private byte[] salt;
    private byte[] encKey;
    private byte[] decKey;
    private long counter;

    private NoiseTransport transport;

    public NoiseHandshake(KeyPair25519 ephemeral, byte[] introHeader) {
        this.ephemeralPriv = ephemeral.privateKey();
        this.hash = id.jawa.core.WaConstants.NOISE_MODE.clone();
        this.salt = hash.clone();
        this.encKey = hash.clone();
        this.decKey = hash.clone();
        this.counter = 0;

        // Both Baileys and whatsmeow mix the intro header AND the client's ephemeral
        // public key into the running hash before any wire bytes flow.
        mixHash(introHeader);
        mixHash(ephemeral.publicKey());
    }

    /** Process the server's HandshakeMessage.serverHello and return the encrypted client-static. */
    public byte[] processServerHello(byte[] serverEphemeral,
                                     byte[] serverStaticCt,
                                     byte[] serverPayloadCt,
                                     KeyPair25519 noiseStaticKey) {
        mixHash(serverEphemeral);
        mixKey(Curve25519.sharedKey(ephemeralPriv, serverEphemeral));      // ee

        byte[] serverStaticPub = decrypt(serverStaticCt);
        mixKey(Curve25519.sharedKey(ephemeralPriv, serverStaticPub));      // es

        byte[] certBytes = decrypt(serverPayloadCt);
        CertChainValidator.validate(serverStaticPub, certBytes);

        byte[] encStatic = encrypt(noiseStaticKey.publicKey());
        mixKey(Curve25519.sharedKey(noiseStaticKey.privateKey(), serverEphemeral)); // se

        return encStatic;
    }

    /** AEAD-seal the given plaintext (handshake phase). After {@link #finish()}, delegates to transport. */
    public byte[] encrypt(byte[] plaintext) {
        if (transport != null) return transport.encrypt(plaintext);
        byte[] iv = AesGcm.noiseIv(counter++);
        byte[] ct = AesGcm.encrypt(encKey, iv, plaintext, hash);
        mixHash(ct);
        return ct;
    }

    /** AEAD-open the given ciphertext (handshake phase). After {@link #finish()}, delegates to transport. */
    public byte[] decrypt(byte[] ciphertext) {
        if (transport != null) return transport.decrypt(ciphertext);
        byte[] iv = AesGcm.noiseIv(counter++);
        byte[] pt = AesGcm.decrypt(decKey, iv, ciphertext, hash);
        mixHash(ciphertext);
        return pt;
    }

    /** Split the chaining key into the two transport keys and enter steady state. */
    public NoiseTransport finish() {
        if (transport != null) return transport;
        byte[] derived = Hkdf.derive(new byte[0], salt, 64);
        byte[] writeKey = Bytes.slice(derived, 0, 32);
        byte[] readKey  = Bytes.slice(derived, 32, 64);
        transport = new NoiseTransport(writeKey, readKey);
        // wipe handshake key material — not strictly required (JVM may keep copies), but tidy.
        Arrays.fill(salt, (byte) 0);
        Arrays.fill(encKey, (byte) 0);
        Arrays.fill(decKey, (byte) 0);
        return transport;
    }

    public NoiseTransport transport() { return transport; }
    public boolean isFinished() { return transport != null; }

    // ---- internals ----

    private void mixHash(byte[] data) {
        hash = Sha256.hash(hash, data);
    }

    /** HKDF(salt=ck, ikm=data) → 64 bytes split 32/32 (ck', k'). Resets handshake counter. */
    private void mixKey(byte[] data) {
        byte[] derived = Hkdf.derive(data, salt, 64);
        salt = Bytes.slice(derived, 0, 32);
        byte[] k = Bytes.slice(derived, 32, 64);
        encKey = k;
        decKey = k;
        counter = 0;
    }
}
