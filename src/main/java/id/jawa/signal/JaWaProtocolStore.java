// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.signal;

import id.jawa.store.AuthCreds;
import id.jawa.util.crypto.Curve25519;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.state.impl.InMemorySignalProtocolStore;
import org.whispersystems.libsignal.util.KeyHelper;

/**
 * Thin wrapper around libsignal's {@link InMemorySignalProtocolStore}, seeded with our
 * long-term identity key and registration id from {@link AuthCreds}.
 *
 * <p>TODO: file persistence so sessions survive process restart. Without it, every
 * connect must rebuild sessions, which works only when the peer has fresh pre-keys
 * available.
 */
public final class JaWaProtocolStore extends InMemorySignalProtocolStore {

    public JaWaProtocolStore(AuthCreds creds) {
        super(toIdentityKeyPair(creds), creds.registrationId);
    }

    private static IdentityKeyPair toIdentityKeyPair(AuthCreds creds) {
        try {
            byte[] prefixedPub = Curve25519.prependType(creds.signedIdentityKey.publicKey());
            IdentityKey pub = new IdentityKey(Curve.decodePoint(prefixedPub, 0));
            return new IdentityKeyPair(pub, Curve.decodePrivatePoint(creds.signedIdentityKey.privateKey()));
        } catch (InvalidKeyException e) {
            throw new IllegalStateException("invalid identity key in AuthCreds", e);
        }
    }

    /** Unused — silence compiler warning if KeyHelper isn't pulled in elsewhere. */
    @SuppressWarnings("unused")
    private static void touchKeyHelper() { KeyHelper.generateIdentityKeyPair(); }
}
