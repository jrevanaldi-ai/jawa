// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.binary;

import id.jawa.util.Jid;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Encodes {@link BinaryNode} trees to the WhatsApp binary wire format.
 *
 * <p>Ports {@code WABinary/encode.ts} (Baileys) and {@code binary/encoder.go} (whatsmeow).
 *
 * <p>Output starts with a 1-byte flag (always {@code 0x00 = raw}; the server may
 * respond with {@code 0x02 = zlib-deflated}, decoded by {@link BinaryDecoder}).
 *
 * <p>Thread-safety: instances are NOT thread-safe; each encode call should use
 * its own encoder (or just call {@link #encode(BinaryNode)}).
 */
public final class BinaryEncoder {

    private final ByteArrayOutputStream buf = new ByteArrayOutputStream(256);

    private BinaryEncoder() {
        buf.write(WaTags.FLAG_RAW);
    }

    /** One-shot encode. */
    public static byte[] encode(BinaryNode node) {
        BinaryEncoder e = new BinaryEncoder();
        e.writeNode(node);
        return e.buf.toByteArray();
    }

    // ---- node ----

    private void writeNode(BinaryNode node) {
        if (node == null || node.tag() == null) {
            throw new IllegalArgumentException("node and tag must be non-null");
        }
        int attrCount = (int) node.attrs().values().stream().filter(v -> v != null).count();
        int hasContent = node.content() == null ? 0 : 1;
        writeListStart(2 * attrCount + 1 + hasContent);
        writeString(node.tag());

        for (var e : node.attrs().entrySet()) {
            if (e.getValue() == null) continue;
            writeString(e.getKey());
            writeString(e.getValue());
        }

        Object content = node.content();
        switch (content) {
            case null -> {}
            case String s -> writeString(s);
            case byte[] b -> { writeByteLength(b.length); buf.writeBytes(b); }
            case List<?> kids -> {
                int validCount = 0;
                for (Object k : kids) if (k instanceof BinaryNode) validCount++;
                writeListStart(validCount);
                for (Object k : kids) if (k instanceof BinaryNode bn) writeNode(bn);
            }
            default -> throw new IllegalArgumentException(
                "invalid content for tag '" + node.tag() + "': " + content.getClass());
        }
    }

    // ---- string ----

    private void writeString(String s) {
        if (s == null) { buf.write(WaTags.LIST_EMPTY); return; }
        if (s.isEmpty()) { writeStringRaw(s); return; }

        int[] idx = WaTokens.INDEX.get(s);
        if (idx != null) {
            if (idx[0] >= 0) buf.write(WaTags.DICTIONARY_0 + idx[0]);
            buf.write(idx[1]);
            return;
        }
        if (isNibble(s)) { writePackedBytes(s, true); return; }
        if (isHex(s))    { writePackedBytes(s, false); return; }

        Jid j = Jid.parse(s);
        if (j != null) { writeJid(j); return; }

        writeStringRaw(s);
    }

    private void writeStringRaw(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeByteLength(bytes.length);
        buf.writeBytes(bytes);
    }

    private void writeJid(Jid j) {
        if (j.hasDevice()) {
            buf.write(WaTags.AD_JID);
            buf.write(j.domainType());
            buf.write(j.device());
            writeString(j.user());
        } else {
            buf.write(WaTags.JID_PAIR);
            if (!j.user().isEmpty()) writeString(j.user()); else buf.write(WaTags.LIST_EMPTY);
            writeString(j.server());
        }
    }

    // ---- length ----

    private void writeByteLength(int length) {
        if (length < 0) throw new IllegalArgumentException("negative length: " + length);
        if (length < 256) {
            buf.write(WaTags.BINARY_8);
            buf.write(length);
        } else if (length < (1 << 20)) {
            buf.write(WaTags.BINARY_20);
            buf.write((length >> 16) & 0x0F);
            buf.write((length >> 8) & 0xFF);
            buf.write(length & 0xFF);
        } else {
            buf.write(WaTags.BINARY_32);
            buf.write((length >> 24) & 0xFF);
            buf.write((length >> 16) & 0xFF);
            buf.write((length >> 8) & 0xFF);
            buf.write(length & 0xFF);
        }
    }

    private void writeListStart(int size) {
        if (size == 0) {
            buf.write(WaTags.LIST_EMPTY);
        } else if (size < 256) {
            buf.write(WaTags.LIST_8);
            buf.write(size);
        } else {
            buf.write(WaTags.LIST_16);
            buf.write((size >> 8) & 0xFF);
            buf.write(size & 0xFF);
        }
    }

    // ---- packed nibble/hex ----

    private static boolean isNibble(String s) {
        if (s.isEmpty() || s.length() > WaTags.PACKED_MAX) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || c == '-' || c == '.')) return false;
        }
        return true;
    }

    private static boolean isHex(String s) {
        if (s.isEmpty() || s.length() > WaTags.PACKED_MAX) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F'))) return false;
        }
        return true;
    }

    private void writePackedBytes(String s, boolean nibble) {
        if (s.length() > WaTags.PACKED_MAX) {
            throw new IllegalArgumentException("string too long to pack: " + s.length());
        }
        buf.write(nibble ? WaTags.NIBBLE_8 : WaTags.HEX_8);
        int rounded = (s.length() + 1) / 2;
        if (s.length() % 2 != 0) rounded |= 0x80;
        buf.write(rounded);

        int pairs = s.length() / 2;
        for (int i = 0; i < pairs; i++) {
            int hi = nibble ? packNibble(s.charAt(2 * i)) : packHex(s.charAt(2 * i));
            int lo = nibble ? packNibble(s.charAt(2 * i + 1)) : packHex(s.charAt(2 * i + 1));
            buf.write((hi << 4) | lo);
        }
        if (s.length() % 2 != 0) {
            int hi = nibble ? packNibble(s.charAt(s.length() - 1)) : packHex(s.charAt(s.length() - 1));
            int lo = nibble ? packNibble('\0') : packHex('\0');
            buf.write((hi << 4) | lo);
        }
    }

    private static int packNibble(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        return switch (c) {
            case '-'  -> 10;
            case '.'  -> 11;
            case '\0' -> 15;
            default   -> throw new IllegalArgumentException("invalid nibble char: " + c);
        };
    }

    private static int packHex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'A' && c <= 'F') return 10 + (c - 'A');
        if (c >= 'a' && c <= 'f') return 10 + (c - 'a');
        if (c == '\0') return 15;
        throw new IllegalArgumentException("invalid hex char: " + c);
    }
}
