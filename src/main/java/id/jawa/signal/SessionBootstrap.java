// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.signal;

import id.jawa.util.Jid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.SessionBuilder;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.UntrustedIdentityException;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks a list of {@link PreKeyBundleFetcher.FetchedBundle}s and installs each one into
 * the given {@link JaWaProtocolStore} via libsignal's {@link SessionBuilder#process(org.whispersystems.libsignal.state.PreKeyBundle)}.
 *
 * <p>After this completes, the store has Signal sessions ready for {@link
 * org.whispersystems.libsignal.SessionCipher} to encrypt outgoing messages addressed
 * to the corresponding {@link SignalProtocolAddress}.
 */
public final class SessionBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(SessionBootstrap.class);

    private SessionBootstrap() {}

    /**
     * For each fetched bundle, build a {@link SessionBuilder} for the matching
     * {@link SignalProtocolAddress} and run {@code process(bundle)}. Returns the
     * addresses for which a session was successfully installed.
     */
    public static List<SignalProtocolAddress> installAll(JaWaProtocolStore store,
                                                         List<PreKeyBundleFetcher.FetchedBundle> bundles) {
        List<SignalProtocolAddress> ok = new ArrayList<>();
        for (var fb : bundles) {
            SignalProtocolAddress addr = addressFor(fb.address());
            SessionBuilder builder = new SessionBuilder(store, addr);
            try {
                builder.process(fb.bundle());
                ok.add(addr);
                LOG.debug("Session installed for {}", addr);
            } catch (InvalidKeyException | UntrustedIdentityException e) {
                LOG.warn("Failed to install session for {}: {}", addr, e.toString());
            }
        }
        return ok;
    }

    /** Build the libsignal address for a JID. Name is the bare user, deviceId is the device suffix. */
    public static SignalProtocolAddress addressFor(Jid jid) {
        return new SignalProtocolAddress(jid.user(), jid.device());
    }
}
