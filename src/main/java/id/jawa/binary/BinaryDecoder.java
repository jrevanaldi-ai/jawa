// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.binary;

import id.jawa.util.Jid;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.InflaterInputStream;

/**
 * Decodes the WhatsApp binary wire format back into {@link BinaryNode} trees.
 *
 * <p>Ports {@code WABinary/decode.ts} (Baileys) and {@code binary/decoder.go} (whatsmeow).
 *
 * <p>The very first byte is a flag: {@code 0x00 = raw}, bit 1 set ({@code & 0x02})
 * indicates the remainder is zlib-deflated (RFC 1950) and is transparently inflated.
 */
public final class BinaryDecoder {

    private final byte[] data;
    private int pos;

    private BinaryDecoder(byte[] data) { this.data = data; this.pos = 0; }

    /** One-shot decode. */
    public static BinaryNode decode(byte[] payload) {
        if (payload == null || payload.length == 0) {
            throw new IllegalArgumentException("empty payload");
        }
        byte flag = payload[0];
        byte[] body;
        if ((flag & WaTags.FLAG_DEFLATE) != 0) {
            try (InflaterInputStream in = new InflaterInputStream(
                    new ByteArrayInputStream(payload, 1, payload.length - 1))) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length * 2);
                in.transferTo(out);
                body = out.toByteArray();
            } catch (IOException e) {
                throw new IllegalStateException("zlib inflate failed", e);
            }
        } else {
            body = new byte[payload.length - 1];
            System.arraycopy(payload, 1, body, 0, body.length);
        }
        return new BinaryDecoder(body).readNode();
    }

    // ---- node ----

    private BinaryNode readNode() {
        int listSize = readListSize(readByte());
        if (listSize == 0) throw new IllegalStateException("invalid node: empty list");
        String tag = readString(readByte());
        if (tag == null || tag.isEmpty()) throw new IllegalStateException("invalid node: empty tag");

        int attrCount = (listSize - 1) >> 1;
        Map<String, String> attrs = attrCount == 0 ? Map.of() : new HashMap<>(attrCount * 2);
        for (int i = 0; i < attrCount; i++) {
            String k = readString(readByte());
            String v = readString(readByte());
            attrs.put(k, v);
        }

        Object content = null;
        if (listSize % 2 == 0) {
            int tagByte = readByte();
            content = switch (tagByte) {
                case WaTags.LIST_EMPTY, WaTags.LIST_8, WaTags.LIST_16 -> readList(tagByte);
                case WaTags.BINARY_8  -> readBytes(readByte());
                case WaTags.BINARY_20 -> readBytes(readInt20());
                case WaTags.BINARY_32 -> readBytes(readInt(4));
                default -> readString(tagByte);
            };
        }
        return new BinaryNode(tag, attrs, content);
    }

    private List<BinaryNode> readList(int tag) {
        int size = readListSize(tag);
        List<BinaryNode> items = new ArrayList<>(size);
        for (int i = 0; i < size; i++) items.add(readNode());
        return items;
    }

    private int readListSize(int tag) {
        return switch (tag) {
            case WaTags.LIST_EMPTY -> 0;
            case WaTags.LIST_8     -> readByte();
            case WaTags.LIST_16    -> readInt(2);
            default -> throw new IllegalStateException("invalid list tag: " + tag);
        };
    }

    // ---- string ----

    private String readString(int tag) {
        if (tag >= 1 && tag < WaTokens.SINGLE.length) return WaTokens.SINGLE[tag];
        return switch (tag) {
            case WaTags.LIST_EMPTY  -> "";
            case WaTags.DICTIONARY_0, WaTags.DICTIONARY_1, WaTags.DICTIONARY_2, WaTags.DICTIONARY_3 ->
                getTokenDouble(tag - WaTags.DICTIONARY_0, readByte());
            case WaTags.BINARY_8  -> readStringFromChars(readByte());
            case WaTags.BINARY_20 -> readStringFromChars(readInt20());
            case WaTags.BINARY_32 -> readStringFromChars(readInt(4));
            case WaTags.JID_PAIR    -> readJidPair();
            case WaTags.AD_JID      -> readAdJid();
            case WaTags.FB_JID      -> readFbJid();
            case WaTags.INTEROP_JID -> readInteropJid();
            case WaTags.HEX_8, WaTags.NIBBLE_8 -> readPacked8(tag);
            default -> throw new IllegalStateException("invalid string tag: " + tag);
        };
    }

    private String getTokenDouble(int dict, int index) {
        if (dict < 0 || dict >= WaTokens.DOUBLE.length) {
            throw new IllegalStateException("invalid double-token dict: " + dict);
        }
        String[] table = WaTokens.DOUBLE[dict];
        if (index < 0 || index >= table.length) {
            throw new IllegalStateException("invalid double-token index: " + index);
        }
        return table[index];
    }

    private String readStringFromChars(int n) {
        byte[] b = readBytes(n);
        return new String(b, StandardCharsets.UTF_8);
    }

    // ---- packed ----

    private String readPacked8(int tag) {
        int startByte = readByte();
        int pairs = startByte & 0x7F;
        StringBuilder sb = new StringBuilder(pairs * 2);
        for (int i = 0; i < pairs; i++) {
            int b = readByte();
            sb.append((char) unpackByte(tag, (b & 0xF0) >> 4));
            sb.append((char) unpackByte(tag, b & 0x0F));
        }
        if ((startByte & 0x80) != 0) sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    private static int unpackByte(int tag, int v) {
        return switch (tag) {
            case WaTags.NIBBLE_8 -> unpackNibble(v);
            case WaTags.HEX_8    -> unpackHex(v);
            default -> throw new IllegalStateException("invalid packed tag: " + tag);
        };
    }

    private static int unpackNibble(int v) {
        if (v >= 0 && v <= 9) return '0' + v;
        return switch (v) {
            case 10 -> '-';
            case 11 -> '.';
            case 15 -> '\0';
            default -> throw new IllegalStateException("invalid nibble: " + v);
        };
    }

    private static int unpackHex(int v) {
        if (v >= 0 && v <= 9)  return '0' + v;
        if (v >= 10 && v < 16) return 'A' + (v - 10);
        throw new IllegalStateException("invalid hex: " + v);
    }

    // ---- jid ----

    private String readJidPair() {
        String user = readString(readByte());
        String server = readString(readByte());
        if (server == null) throw new IllegalStateException("invalid jid pair");
        return (user == null ? "" : user) + "@" + server;
    }

    private String readAdJid() {
        int domainType = readByte();
        int device = readByte();
        String user = readString(readByte());
        String server = Jid.serverForDomain(domainType, Jid.SERVER_WHATSAPP);
        StringBuilder sb = new StringBuilder();
        sb.append(user == null ? "" : user);
        if (device != 0) sb.append(':').append(device);
        sb.append('@').append(server);
        return sb.toString();
    }

    private String readFbJid() {
        String user = readString(readByte());
        int device = readInt(2);
        String server = readString(readByte());
        return user + ":" + device + "@" + server;
    }

    private String readInteropJid() {
        String user = readString(readByte());
        int device = readInt(2);
        int integrator = readInt(2);
        String server = "interop";
        int mark = pos;
        try {
            server = readString(readByte());
        } catch (RuntimeException e) {
            pos = mark;
        }
        return integrator + "-" + user + ":" + device + "@" + server;
    }

    // ---- low-level ----

    private int readByte() {
        if (pos >= data.length) throw new IllegalStateException("end of stream");
        return data[pos++] & 0xFF;
    }

    private byte[] readBytes(int n) {
        if (pos + n > data.length) throw new IllegalStateException("end of stream");
        byte[] out = new byte[n];
        System.arraycopy(data, pos, out, 0, n);
        pos += n;
        return out;
    }

    private int readInt(int n) {
        int v = 0;
        for (int i = 0; i < n; i++) {
            v = (v << 8) | readByte();
        }
        return v;
    }

    private int readInt20() {
        int b0 = readByte();
        int b1 = readByte();
        int b2 = readByte();
        return ((b0 & 0x0F) << 16) | (b1 << 8) | b2;
    }
}
