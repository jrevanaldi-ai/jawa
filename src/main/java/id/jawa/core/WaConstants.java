// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.core;

import id.jawa.util.Bytes;

/**
 * WhatsApp Web protocol constants — endpoint, Noise pattern, intro header, server static
 * cert pubkey. Mirrors Baileys (src/Defaults/index.ts) and whatsmeow (socket/constants.go).
 *
 * <p>The version array tracks WA Web's wire protocol version; bump if the server
 * rejects {@code <stream:error>} as "obsolete client" — pull the latest from
 * Baileys' {@code Defaults/baileys-version.json} or whatsmeow's {@code store.WAVersion}.
 */
public final class WaConstants {

    public static final String ORIGIN = "https://web.whatsapp.com";
    public static final String WS_URL = "wss://web.whatsapp.com/ws/chat";

    public static final byte WA_MAGIC = 6;
    public static final int  DICT_VERSION = 3;
    /** {@code 'W', 'A', 6, 3} — first 4 bytes the client writes on the WebSocket. */
    public static final byte[] WA_HEADER = new byte[] { 'W', 'A', WA_MAGIC, (byte) DICT_VERSION };

    /** Noise protocol name padded to 32 bytes with NULs — used directly as initial hash. */
    public static final byte[] NOISE_MODE = pad32("Noise_XX_25519_AESGCM_SHA256");

    public static final int FRAME_MAX_SIZE = 1 << 24;
    public static final int FRAME_LENGTH_SIZE = 3;

    /** Server static key signature chain anchor. */
    public static final int WA_CERT_SERIAL = 0;
    public static final String WA_CERT_ISSUER = "WhatsAppLongTerm1";
    public static final byte[] WA_CERT_PUBLIC_KEY = Bytes.fromHex(
        "142375574d0a587166aae71ebe516437c4a28b73e3695c6ce1f7f9545da8ee6b"
    );

    /** ADV signature prefixes (mixed into ADV identity payloads before signing). */
    public static final byte[] ADV_ACCOUNT_SIG_PREFIX        = new byte[] { 6, 0 };
    public static final byte[] ADV_DEVICE_SIG_PREFIX         = new byte[] { 6, 1 };
    public static final byte[] ADV_HOSTED_ACCOUNT_SIG_PREFIX = new byte[] { 6, 5 };
    public static final byte[] ADV_HOSTED_DEVICE_SIG_PREFIX  = new byte[] { 6, 6 };

    /** WA Web version. Bump if "client too old" responses come back. */
    public static final int[] WA_VERSION = new int[] { 2, 3000, 1035194821 };

    private WaConstants() {}

    private static byte[] pad32(String s) {
        byte[] raw = Bytes.utf8(s);
        if (raw.length == 32) return raw;
        if (raw.length > 32) throw new IllegalArgumentException("noise pattern too long");
        byte[] out = new byte[32];
        System.arraycopy(raw, 0, out, 0, raw.length);
        return out;
    }
}
