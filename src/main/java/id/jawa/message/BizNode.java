// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.message;

import id.jawa.binary.BinaryNode;
import id.jawa.proto.Wa;
import id.jawa.util.Bytes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Build the {@code <biz>} binary node WhatsApp's server expects alongside a
 * {@code <message>} stanza carrying an interactive payload (buttons / list /
 * native flow / template). Without this node the server accepts the stanza but
 * the receiver app silently drops the interactive surface and the message
 * renders blank.
 *
 * <p>Shape mirrors the {@code getBizBinaryNode} routine in itsliaaa/baileys —
 * see {@code lib/WABinary/generic-utils.js}. The two static children always
 * present are a per-message {@code quality_control} (with a random 20-byte hex
 * {@code decision_id}) and either an {@code interactive native_flow=mixed}
 * marker (for buttons/template) or a {@code list type=product_list} marker
 * (for list messages).
 */
public final class BizNode {

    private BizNode() {}

    /**
     * Build the {@code <biz>} node for a given outgoing {@link Wa.Message}, or
     * {@code null} if the message carries no interactive surface and the node
     * isn't needed.
     */
    public static BinaryNode buildIfNeeded(Wa.Message message) {
        if (message == null) return null;
        boolean isButtons     = message.hasButtonsMessage();
        boolean isList        = message.hasListMessage();
        boolean isInteractive = message.hasInteractiveMessage();
        boolean isTemplate    = message.hasTemplateMessage();
        if (!isButtons && !isList && !isInteractive && !isTemplate) return null;

        Map<String, String> bizAttrs = new LinkedHashMap<>();
        bizAttrs.put("actual_actors",   "2");
        bizAttrs.put("host_storage",    "2");
        bizAttrs.put("privacy_mode_ts", Long.toString(System.currentTimeMillis() / 1_000));

        List<BinaryNode> children = new ArrayList<>();
        if (isList) {
            children.add(new BinaryNode("list",
                Map.of("v", "2", "type", "product_list"), null));
        } else {
            children.add(new BinaryNode("interactive",
                Map.of("type", "native_flow", "v", "1"),
                List.of(new BinaryNode("native_flow",
                    Map.of("v", "9", "name", "mixed"), null))));
        }
        children.add(qualityControl());
        return new BinaryNode("biz", bizAttrs, children);
    }

    private static BinaryNode qualityControl() {
        String decisionId = Bytes.toHex(Bytes.random(20));
        BinaryNode decisionSource = new BinaryNode("decision_source",
            Map.of("value", "df"), null);
        return new BinaryNode("quality_control",
            Map.of(
                "decision_id",  decisionId,
                "source_type",  "third_party"
            ),
            List.of(decisionSource));
    }
}
