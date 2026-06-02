// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.store;

import id.jawa.util.crypto.Curve25519;
import id.jawa.util.crypto.KeyPair25519;

/**
 * A Signal-style signed pre-key: a Curve25519 keypair with a signature over its
 * (KEY_BUNDLE_TYPE-prefixed) public key produced by the long-term identity key.
 */
public record SignedPreKey(int keyId, KeyPair25519 keyPair, byte[] signature) {

    public static SignedPreKey generate(int keyId, KeyPair25519 identityKey) {
        KeyPair25519 pre = Curve25519.generateKeyPair();
        byte[] toSign = Curve25519.prependType(pre.publicKey());
        byte[] sig = Curve25519.sign(identityKey.privateKey(), toSign);
        return new SignedPreKey(keyId, pre, sig);
    }
}
