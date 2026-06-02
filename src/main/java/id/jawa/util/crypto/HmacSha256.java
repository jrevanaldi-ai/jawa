// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.util.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

/** HMAC-SHA256. */
public final class HmacSha256 {
    private HmacSha256() {}

    public static byte[] sign(byte[] key, byte[]... messages) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            for (byte[] m : messages) if (m != null) mac.update(m);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 failed", e);
        }
    }
}
