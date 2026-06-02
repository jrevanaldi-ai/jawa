// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.util.crypto;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-256. */
public final class Sha256 {
    private Sha256() {}

    public static byte[] hash(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) if (p != null) md.update(p);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
