// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.message;

import id.jawa.binary.BinaryDecoder;
import id.jawa.binary.BinaryEncoder;
import id.jawa.binary.BinaryNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UsyncQueryTest {

    @Test
    void deviceListQueryHasExpectedShape() {
        BinaryNode iq = UsyncQuery.buildDeviceListQuery("iq1", "sid1",
            List.of("628111@s.whatsapp.net", "628222@s.whatsapp.net"));

        assertThat(iq.tag()).isEqualTo("iq");
        assertThat(iq.attrs())
            .containsEntry("xmlns", "usync")
            .containsEntry("type", "get")
            .containsEntry("id", "iq1");

        BinaryNode usync = iq.child("usync");
        assertThat(usync).isNotNull();
        assertThat(usync.attr("sid")).isEqualTo("sid1");
        assertThat(usync.attr("mode")).isEqualTo("query");
        assertThat(usync.attr("last")).isEqualTo("true");

        BinaryNode query = usync.child("query");
        assertThat(query.child("devices").attr("version")).isEqualTo("2");

        BinaryNode list = usync.child("list");
        assertThat(list.children("user")).hasSize(2);
        assertThat(list.children("user").get(0).attr("jid")).isEqualTo("628111@s.whatsapp.net");
    }

    @Test
    void roundTripsThroughBinaryCodec() {
        BinaryNode iq = UsyncQuery.buildDeviceListQuery("rt", "sid",
            List.of("628aaa@s.whatsapp.net"));
        byte[] bytes = BinaryEncoder.encode(iq);
        BinaryNode decoded = BinaryDecoder.decode(bytes);

        assertThat(decoded.tag()).isEqualTo("iq");
        assertThat(decoded.child("usync").child("list").child("user").attr("jid"))
            .isEqualTo("628aaa@s.whatsapp.net");
    }

    @Test
    void parseDeviceListResultExtractsDevices() {
        BinaryNode result = new BinaryNode("iq", Map.of("type", "result", "id", "x"), List.of(
            new BinaryNode("usync", Map.of("sid", "x", "mode", "query"), List.of(
                new BinaryNode("list", Map.of(), List.of(
                    new BinaryNode("user", Map.of("jid", "628111@s.whatsapp.net"), List.of(
                        new BinaryNode("devices", Map.of(), List.of(
                            new BinaryNode("device-list", Map.of(), List.of(
                                new BinaryNode("device", Map.of("id", "0",  "key-index", "0"), null),
                                new BinaryNode("device", Map.of("id", "56", "key-index", "1"), null)
                            ))
                        ))
                    ))
                ))
            ))
        ));

        Map<String, List<DeviceInfo>> parsed = UsyncQuery.parseDeviceListResult(result);
        assertThat(parsed).containsOnlyKeys("628111@s.whatsapp.net");
        List<DeviceInfo> devices = parsed.get("628111@s.whatsapp.net");
        assertThat(devices).hasSize(2);
        assertThat(devices.get(0).id()).isZero();
        assertThat(devices.get(1).id()).isEqualTo(56);
        assertThat(devices.get(1).keyIndex()).isEqualTo(1);
    }

    @Test
    void emptyResultParsesToEmptyMap() {
        BinaryNode result = new BinaryNode("iq",
            Map.of("type", "result", "id", "x"),
            List.of(new BinaryNode("usync", Map.of(), List.of())));
        assertThat(UsyncQuery.parseDeviceListResult(result)).isEmpty();
    }

    @Test
    void nonResultIqThrows() {
        BinaryNode err = new BinaryNode("iq", Map.of("type", "error"), null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> UsyncQuery.parseDeviceListResult(err))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
