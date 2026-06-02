// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.message;

import id.jawa.binary.BinaryNode;
import id.jawa.util.Jid;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds and parses {@code <iq xmlns=usync>} stanzas — the way the client asks the server
 * to enumerate a user's devices (and many other contact-shape protocols which we'll add later).
 *
 * <p>Ports the device-protocol slice of Baileys' {@code src/WAUSync/}.
 *
 * <p>Wire shape (device-list query):
 * <pre>{@code
 * <iq xmlns="usync" type="get" to="s.whatsapp.net" id="...">
 *   <usync sid="<sid>" mode="query" last="true" context="interactive">
 *     <query>
 *       <devices version="2"/>
 *     </query>
 *     <list>
 *       <user jid="628...@s.whatsapp.net"/>
 *       ... (more users)
 *     </list>
 *   </usync>
 * </iq>
 * }</pre>
 */
public final class UsyncQuery {

    private UsyncQuery() {}

    /** Build a device-list query for one or more target JIDs. */
    public static BinaryNode buildDeviceListQuery(String iqId, String sid, Collection<String> targetJids) {
        List<BinaryNode> userNodes = new ArrayList<>(targetJids.size());
        for (String j : targetJids) {
            userNodes.add(new BinaryNode("user", Map.of("jid", j), null));
        }

        BinaryNode usync = new BinaryNode("usync",
            Map.of(
                "sid",     sid,
                "mode",    "query",
                "last",    "true",
                "index",   "0",
                "context", "interactive"
            ),
            List.of(
                new BinaryNode("query", Map.of(), List.of(
                    new BinaryNode("devices", Map.of("version", "2"), null)
                )),
                new BinaryNode("list", Map.of(), userNodes)
            ));

        return new BinaryNode("iq",
            Map.of(
                "xmlns", "usync",
                "type",  "get",
                "to",    Jid.SERVER_WHATSAPP,
                "id",    iqId
            ),
            List.of(usync));
    }

    /**
     * Parse the {@code <iq type=result>} response from a device-list query.
     * Returns map of {@code targetJid → ordered device list}. Missing users / errored users
     * map to an empty list.
     */
    public static Map<String, List<DeviceInfo>> parseDeviceListResult(BinaryNode resultIq) {
        if (!"result".equals(resultIq.attr("type"))) {
            throw new IllegalArgumentException("not a usync result iq: " + resultIq);
        }
        BinaryNode usync = resultIq.child("usync");
        if (usync == null) return Map.of();
        BinaryNode list = usync.child("list");
        if (list == null) return Map.of();

        Map<String, List<DeviceInfo>> out = new LinkedHashMap<>();
        for (BinaryNode user : list.children("user")) {
            String jid = user.attr("jid");
            if (jid == null) continue;
            BinaryNode devices = user.child("devices");
            BinaryNode deviceList = devices == null ? null : devices.child("device-list");
            List<DeviceInfo> dl = new ArrayList<>();
            if (deviceList != null) {
                for (BinaryNode dev : deviceList.children("device")) {
                    String idStr = dev.attr("id");
                    if (idStr == null) continue;
                    int id = Integer.parseInt(idStr);
                    int keyIndex = parseIntOr(dev.attr("key-index"), 0);
                    boolean hosted = "true".equals(dev.attr("is_hosted"));
                    dl.add(new DeviceInfo(id, keyIndex, hosted));
                }
            }
            out.put(jid, dl);
        }
        return out;
    }

    private static int parseIntOr(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }
}
