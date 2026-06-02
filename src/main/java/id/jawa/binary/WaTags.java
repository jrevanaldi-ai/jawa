// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.binary;

/**
 * Tag-byte constants used by the WhatsApp binary protocol.
 * Mirrors Baileys (WABinary/constants.ts) and whatsmeow (binary/token/token.go).
 */
public final class WaTags {
    /** Empty list / empty string sentinel. */
    public static final int LIST_EMPTY = 0;

    /** XML stream control tokens (single-byte token indices). */
    public static final int XML_STREAM_START = 1;
    public static final int XML_STREAM_END = 2;

    /** Dictionary selector for double-byte tokens. */
    public static final int DICTIONARY_0 = 236;
    public static final int DICTIONARY_1 = 237;
    public static final int DICTIONARY_2 = 238;
    public static final int DICTIONARY_3 = 239;

    /** JID variants. */
    public static final int INTEROP_JID = 245;
    public static final int FB_JID = 246;
    public static final int AD_JID = 247;
    public static final int JID_PAIR = 250;

    /** Lists. */
    public static final int LIST_8 = 248;
    public static final int LIST_16 = 249;

    /** Packed strings. */
    public static final int HEX_8 = 251;
    public static final int NIBBLE_8 = 255;
    public static final int PACKED_MAX = 127;

    /** Raw binary lengths. */
    public static final int BINARY_8 = 252;
    public static final int BINARY_20 = 253;
    public static final int BINARY_32 = 254;

    /** Compression flag in the very first byte of a payload. */
    public static final int FLAG_RAW = 0x00;
    public static final int FLAG_DEFLATE = 0x02;

    private WaTags() {}
}
