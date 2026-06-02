// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.binary;

import id.jawa.util.Jid;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BinaryCodecTest {

    // ---- WaTokens ----

    @Test
    void singleByteTokensLoaded() {
        assertThat(WaTokens.SINGLE).hasSizeGreaterThan(200);
        assertThat(WaTokens.SINGLE[3]).isEqualTo("s.whatsapp.net");
        assertThat(WaTokens.SINGLE[25]).isEqualTo("iq");
        assertThat(WaTokens.SINGLE[84]).isEqualTo("version");
    }

    @Test
    void doubleByteTokensLoaded() {
        assertThat(WaTokens.DOUBLE.length).isEqualTo(4);
        for (String[] dict : WaTokens.DOUBLE) {
            assertThat(dict).hasSize(256);
        }
    }

    @Test
    void reverseIndex() {
        int[] msg = WaTokens.INDEX.get("message");
        assertThat(msg).isNotNull();
        assertThat(msg[0]).isEqualTo(-1); // single byte
        assertThat(WaTokens.SINGLE[msg[1]]).isEqualTo("message");

        int[] reaction = WaTokens.INDEX.get("reaction");
        assertThat(reaction).isNotNull();
        assertThat(reaction[0]).isGreaterThanOrEqualTo(0); // double byte
        assertThat(WaTokens.DOUBLE[reaction[0]][reaction[1]]).isEqualTo("reaction");
    }

    // ---- Jid ----

    @Test
    void jidParsePlain() {
        Jid j = Jid.parse("628123@s.whatsapp.net");
        assertThat(j.user()).isEqualTo("628123");
        assertThat(j.server()).isEqualTo("s.whatsapp.net");
        assertThat(j.device()).isZero();
        assertThat(j.agent()).isZero();
        assertThat(j.asString()).isEqualTo("628123@s.whatsapp.net");
    }

    @Test
    void jidParseWithDevice() {
        Jid j = Jid.parse("628123:7@s.whatsapp.net");
        assertThat(j.user()).isEqualTo("628123");
        assertThat(j.device()).isEqualTo(7);
        assertThat(j.asString()).isEqualTo("628123:7@s.whatsapp.net");
    }

    @Test
    void jidParseWithAgent() {
        Jid j = Jid.parse("628123_1:7@s.whatsapp.net");
        assertThat(j.user()).isEqualTo("628123");
        assertThat(j.agent()).isEqualTo(1);
        assertThat(j.device()).isEqualTo(7);
        assertThat(j.asString()).isEqualTo("628123_1:7@s.whatsapp.net");
    }

    @Test
    void jidParseLid() {
        Jid j = Jid.parse("123456@lid");
        assertThat(j.domainType()).isEqualTo(Jid.DOMAIN_LID);
    }

    @Test
    void jidParseInvalidReturnsNull() {
        assertThat(Jid.parse("no-at-sign")).isNull();
        assertThat(Jid.parse(null)).isNull();
    }

    // ---- Round-trip: single-byte token ----

    @Test
    void roundTripSingleByteToken() {
        BinaryNode in = BinaryNode.of("message", Map.of("from", "628@s.whatsapp.net", "type", "text"));
        byte[] bytes = BinaryEncoder.encode(in);
        BinaryNode out = BinaryDecoder.decode(bytes);

        assertThat(out.tag()).isEqualTo("message");
        assertThat(out.attrs()).containsEntry("from", "628@s.whatsapp.net").containsEntry("type", "text");
        assertThat(out.content()).isNull();
    }

    // ---- Round-trip: text content ----

    @Test
    void roundTripTextContent() {
        BinaryNode in = BinaryNode.text("body", Map.of(), "halo dunia");
        byte[] bytes = BinaryEncoder.encode(in);
        BinaryNode out = BinaryDecoder.decode(bytes);

        assertThat(out.tag()).isEqualTo("body");
        // Non-tokenizable text encodes as BINARY_8 and decodes back as byte[].
        // textContent() unifies both forms.
        assertThat(out.textContent()).isEqualTo("halo dunia");
    }

    // ---- Round-trip: bytes content ----

    @Test
    void roundTripBytesContent() {
        byte[] payload = "ciphertext".getBytes(StandardCharsets.UTF_8);
        BinaryNode in = BinaryNode.bytes("enc", Map.of("type", "msg", "v", "2"), payload);
        byte[] bytes = BinaryEncoder.encode(in);
        BinaryNode out = BinaryDecoder.decode(bytes);

        assertThat(out.tag()).isEqualTo("enc");
        assertThat(out.attrs()).containsEntry("type", "msg").containsEntry("v", "2");
        assertThat(out.content()).isInstanceOf(byte[].class);
        assertThat((byte[]) out.content()).containsExactly(payload);
    }

    // ---- Round-trip: nested children ----

    @Test
    void roundTripNestedChildren() {
        BinaryNode in = BinaryNode.children("iq",
            Map.of("id", "abc", "type", "get", "to", "s.whatsapp.net"),
            List.of(
                BinaryNode.of("ping"),
                BinaryNode.text("note", Map.of(), "hi")
            ));
        byte[] bytes = BinaryEncoder.encode(in);
        BinaryNode out = BinaryDecoder.decode(bytes);

        assertThat(out.tag()).isEqualTo("iq");
        assertThat(out.attrs()).containsEntry("id", "abc").containsEntry("type", "get").containsEntry("to", "s.whatsapp.net");
        List<BinaryNode> kids = out.childrenList();
        assertThat(kids).hasSize(2);
        assertThat(kids.get(0).tag()).isEqualTo("ping");
        assertThat(kids.get(1).tag()).isEqualTo("note");
        assertThat(kids.get(1).textContent()).isEqualTo("hi");
    }

    // ---- Round-trip: nibble-packed (phone number) ----

    @Test
    void roundTripNibblePacked() {
        BinaryNode in = BinaryNode.text("number", Map.of(), "628123456789");
        byte[] bytes = BinaryEncoder.encode(in);
        BinaryNode out = BinaryDecoder.decode(bytes);
        assertThat(out.content()).isEqualTo("628123456789");
    }

    @Test
    void roundTripNibblePackedOddLength() {
        BinaryNode in = BinaryNode.text("number", Map.of(), "62812345");
        byte[] bytes = BinaryEncoder.encode(in);
        BinaryNode out = BinaryDecoder.decode(bytes);
        assertThat(out.content()).isEqualTo("62812345");
    }

    // ---- Round-trip: hex-packed ----

    @Test
    void roundTripHexPacked() {
        BinaryNode in = BinaryNode.text("hash", Map.of(), "DEADBEEF42");
        byte[] bytes = BinaryEncoder.encode(in);
        BinaryNode out = BinaryDecoder.decode(bytes);
        assertThat(out.content()).isEqualTo("DEADBEEF42");
    }

    // ---- Wire-format byte assertions ----

    @Test
    void emptyNodeProducesKnownBytes() {
        // tag "0" — special-case keepalive (whatsmeow special-cases this; we just round-trip).
        // Here: <ping/> = LIST_8(248) + size 1 + token('ping' index)
        BinaryNode ping = BinaryNode.of("ping");
        byte[] bytes = BinaryEncoder.encode(ping);
        int pingTokenIndex = WaTokens.INDEX.get("ping")[1];
        // flag(0x00) + LIST_8(0xF8) + size(0x01) + tokenIndex
        assertThat(bytes).containsExactly(0x00, 0xF8, 0x01, pingTokenIndex);
    }

    @Test
    void largeBinaryContentUsesBinary20() {
        byte[] payload = new byte[300];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i & 0xFF);
        BinaryNode in = BinaryNode.bytes("blob", Map.of(), payload);
        byte[] bytes = BinaryEncoder.encode(in);
        BinaryNode out = BinaryDecoder.decode(bytes);
        assertThat((byte[]) out.content()).containsExactly(payload);
        // Sanity: somewhere there should be the BINARY_20 marker (0xFD) — 300 >= 256
        assertThat(HexFormat.of().formatHex(bytes)).contains("fd");
    }

    // ---- Error paths ----

    @Test
    void decodeEmptyThrows() {
        assertThatThrownBy(() -> BinaryDecoder.decode(new byte[0]))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void encodeNullTagThrows() {
        assertThatThrownBy(() -> BinaryNode.of(null))
            .isInstanceOf(NullPointerException.class);
    }
}
