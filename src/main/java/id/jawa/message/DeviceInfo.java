// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.message;

import id.jawa.util.Jid;

/**
 * A single device entry returned by a {@code <devices>} USync query.
 *
 * @param id        device id (0 = primary phone, &gt;0 = linked devices)
 * @param keyIndex  ADV signed key-index from the server
 * @param hosted    {@code true} if the device belongs to a hosted account
 */
public record DeviceInfo(int id, int keyIndex, boolean hosted) {

    /** Build the AD-JID for this device under the given owner's user/server. */
    public Jid asJid(String user, String server) {
        int domain = switch (server) {
            case Jid.SERVER_LID         -> Jid.DOMAIN_LID;
            case Jid.SERVER_HOSTED      -> Jid.DOMAIN_HOSTED;
            case Jid.SERVER_HOSTED_LID  -> Jid.DOMAIN_HOSTED_LID;
            default                     -> Jid.DOMAIN_WHATSAPP;
        };
        return new Jid(user, server, id, 0, domain);
    }
}
