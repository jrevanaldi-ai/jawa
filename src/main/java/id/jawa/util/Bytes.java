// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.util;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Small byte-array utilities (concat, slice, random, base64). */
public final class Bytes {
    private static final SecureRandom RNG = new SecureRandom();

    private Bytes() {}

    public static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) if (p != null) total += p.length;
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] p : parts) {
            if (p == null) continue;
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    public static byte[] slice(byte[] src, int from, int to) {
        if (from < 0 || to > src.length || from > to) {
            throw new IllegalArgumentException("invalid slice [" + from + ", " + to + ") of length " + src.length);
        }
        byte[] out = new byte[to - from];
        System.arraycopy(src, from, out, 0, to - from);
        return out;
    }

    public static byte[] random(int length) {
        byte[] b = new byte[length];
        RNG.nextBytes(b);
        return b;
    }

    public static String toHex(byte[] b) { return HexFormat.of().formatHex(b); }
    public static byte[] fromHex(String s) { return HexFormat.of().parseHex(s); }
    public static String toBase64(byte[] b) { return Base64.getEncoder().encodeToString(b); }
    public static byte[] fromBase64(String s) { return Base64.getDecoder().decode(s); }
    public static String toBase64Url(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    public static byte[] fromBase64Url(String s) { return Base64.getUrlDecoder().decode(s); }
    public static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    public static String fromUtf8(byte[] b) { return new String(b, StandardCharsets.UTF_8); }
}
