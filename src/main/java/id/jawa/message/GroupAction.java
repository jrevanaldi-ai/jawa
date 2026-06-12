// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.message;

import id.jawa.binary.BinaryNode;
import id.jawa.util.Jid;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builders for {@code <iq xmlns="w:g2" type="set">} stanzas that mutate group state:
 * create, leave, add / remove / promote / demote participants, set subject, set
 * description. Each builder returns the IQ ready for {@link
 * id.jawa.core.JaWaClient#sendIqAsync(BinaryNode)}.
 */
public final class GroupAction {

    private GroupAction() {}

    /** Add / remove / promote / demote — the four participant-change actions. */
    public enum ParticipantChange {
        ADD("add"),
        REMOVE("remove"),
        PROMOTE("promote"),
        DEMOTE("demote");

        final String tag;

        ParticipantChange(String tag) { this.tag = tag; }
    }

    /** Create a new group. {@code participantBareJids} must NOT include our own JID. */
    public static BinaryNode buildCreate(String iqId, String name, List<String> participantBareJids,
                                         String createKey) {
        List<BinaryNode> children = new ArrayList<>();
        for (String jid : participantBareJids) {
            children.add(BinaryNode.of("participant", Map.of("jid", jid)));
        }
        BinaryNode create = new BinaryNode("create",
            Map.of("subject", name, "key", createKey),
            children);
        return iq(iqId, Jid.SERVER_GROUP, create);
    }

    /** Leave a group. */
    public static BinaryNode buildLeave(String iqId, String groupJid) {
        BinaryNode group = BinaryNode.of("group", Map.of("id", groupJid));
        BinaryNode leave = new BinaryNode("leave", Map.of(), List.of(group));
        return iq(iqId, Jid.SERVER_GROUP, leave);
    }

    /** Add / remove / promote / demote participants. */
    public static BinaryNode buildParticipantChange(String iqId, String groupJid,
                                                    ParticipantChange action,
                                                    List<String> participantBareJids) {
        List<BinaryNode> children = new ArrayList<>();
        for (String jid : participantBareJids) {
            children.add(BinaryNode.of("participant", Map.of("jid", jid)));
        }
        BinaryNode actionNode = new BinaryNode(action.tag, Map.of(), children);
        return iq(iqId, groupJid, actionNode);
    }

    /** Set the group subject (name). */
    public static BinaryNode buildSetSubject(String iqId, String groupJid, String subject) {
        BinaryNode subj = new BinaryNode("subject", Map.of(), subject);
        return iq(iqId, groupJid, subj);
    }

    /**
     * Set or clear the group description (topic). Pass {@code null} or empty
     * {@code body} to clear; {@code previousId} is the previous description's id
     * (caller should fetch via {@link GroupListQuery} or track from a prior call).
     */
    public static BinaryNode buildSetDescription(String iqId, String groupJid,
                                                  String body, String previousId, String newId) {
        java.util.LinkedHashMap<String, String> attrs = new java.util.LinkedHashMap<>();
        attrs.put("id", newId);
        if (previousId != null && !previousId.isEmpty()) attrs.put("prev", previousId);
        BinaryNode description;
        if (body == null || body.isEmpty()) {
            attrs.put("delete", "true");
            description = new BinaryNode("description", attrs, null);
        } else {
            BinaryNode bodyNode = new BinaryNode("body", Map.of(), body);
            description = new BinaryNode("description", attrs, List.of(bodyNode));
        }
        return iq(iqId, groupJid, description);
    }

    private static BinaryNode iq(String iqId, String toJid, BinaryNode payload) {
        return new BinaryNode("iq",
            Map.of(
                "id",    iqId,
                "type",  "set",
                "xmlns", "w:g2",
                "to",    toJid
            ),
            List.of(payload));
    }
}
