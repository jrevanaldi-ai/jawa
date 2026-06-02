// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.store;

import id.jawa.util.Bytes;
import id.jawa.util.crypto.Curve25519;
import id.jawa.util.crypto.KeyPair25519;

import java.security.SecureRandom;

/**
 * Persistent authentication state for a JaWa session — everything needed to
 * reconnect without re-pairing.
 *
 * <p>Mirrors Baileys' {@code AuthenticationCreds} and whatsmeow's persisted device fields.
 * Fields marked "filled after pairing" are {@code null}/empty on fresh creds and are
 * populated by {@link id.jawa.pair.PairingHandler} once the QR scan completes.
 */
public final class AuthCreds {

    /** Long-term Noise (transport) static keypair. */
    public KeyPair25519 noiseKey;
    /** Long-term Signal identity key (also signs ADV chain). */
    public KeyPair25519 signedIdentityKey;
    /** First signed pre-key, uploaded during register. */
    public SignedPreKey signedPreKey;
    /** WhatsApp registration id (uint32, persisted as int). */
    public int registrationId;
    /** ADV shared secret (32 random bytes, used to HMAC the deviceIdentity). */
    public byte[] advSecretKey;

    /** Counter for the next pre-key id to generate. */
    public int nextPreKeyId = 1;
    /** First pre-key id not yet uploaded to the server. */
    public int firstUnuploadedPreKeyId = 1;
    /** Account sync counter (incremented per session sync). */
    public int accountSyncCounter = 0;

    // --- filled after pairing ---

    /** Serialized {@code ADVSignedDeviceIdentity} from the server, post-pair. */
    public byte[] account;
    /** Own JID (e.g. {@code 628123:7@s.whatsapp.net}). */
    public String meJid;
    /** Own LID (multi-device identity). */
    public String meLid;
    /** Pushname (display name). */
    public String pushName;
    /** Linked phone's platform string ("android", "iphone", ...). */
    public String platform;

    public AuthCreds() {}

    /** Generate a fresh set of credentials for a brand-new pairing. */
    public static AuthCreds generate() {
        AuthCreds c = new AuthCreds();
        c.noiseKey         = Curve25519.generateKeyPair();
        c.signedIdentityKey = Curve25519.generateKeyPair();
        c.signedPreKey     = SignedPreKey.generate(1, c.signedIdentityKey);
        // WA's registrationId is uint16 in the Signal world; whatsmeow uses uint32.
        // Match Baileys: random 16-bit unsigned.
        c.registrationId   = new SecureRandom().nextInt(0xFFFF + 1);
        c.advSecretKey     = Bytes.random(32);
        return c;
    }
}
