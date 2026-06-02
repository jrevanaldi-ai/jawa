// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.store;

import java.io.IOException;

/** Pluggable persistence for {@link AuthCreds}. */
public interface AuthStore {
    /** Load existing creds or {@code null} if none. */
    AuthCreds load() throws IOException;

    /** Persist creds atomically. */
    void save(AuthCreds c) throws IOException;

    /** {@code true} if creds exist and contain a paired account (no QR needed). */
    boolean isPaired() throws IOException;
}
