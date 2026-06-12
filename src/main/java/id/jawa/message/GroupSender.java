// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.message;

import com.google.protobuf.ByteString;
import id.jawa.binary.BinaryNode;
import id.jawa.proto.Wa;
import id.jawa.signal.JaWaProtocolStore;
import id.jawa.signal.SessionBootstrap;
import id.jawa.store.AuthCreds;
import id.jawa.util.Jid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.libsignal.NoSessionException;
import org.whispersystems.libsignal.SessionCipher;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.UntrustedIdentityException;
import org.whispersystems.libsignal.groups.GroupCipher;
import org.whispersystems.libsignal.groups.GroupSessionBuilder;
import org.whispersystems.libsignal.groups.SenderKeyName;
import org.whispersystems.libsignal.groups.state.SenderKeyStore;
import org.whispersystems.libsignal.protocol.CiphertextMessage;
import org.whispersystems.libsignal.protocol.SenderKeyDistributionMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Encrypt a {@link Wa.Message} for a WhatsApp group and pack it into a
 * single {@code <message to=<group>@g.us>} stanza.
 *
 * <p>Group send is a hybrid:
 * <ul>
 *   <li>The user-visible message is encrypted ONCE with a {@link GroupCipher} keyed by
 *       our sender-key for this group, producing a single {@code <enc type=skmsg>}.</li>
 *   <li>Our {@link SenderKeyDistributionMessage} (the seed every group member needs to
 *       derive the same sender-key state we just used) is wrapped in a
 *       {@code Wa.Message{senderKeyDistributionMessage}} and encrypted PER-RECIPIENT-DEVICE
 *       with a regular {@link SessionCipher}, riding inside {@code <participants>}.</li>
 * </ul>
 *
 * <p>Stanza shape (ports {@code Client.sendGroupMessage} in whatsmeow's send.go and
 * Baileys' {@code relayMessage}/{@code createParticipantNodes} group path):
 * <pre>{@code
 * <message to=<group>@g.us id=... type=text>
 *   <participants>
 *     <to jid=<participant-device-jid>>
 *       <enc v="2" type="pkmsg|msg">{Signal-encrypted Wa.Message with the SKDM}</enc>
 *     </to>
 *     ... (one per participant device)
 *   </participants>
 *   <enc v="2" type="skmsg">{GroupCipher-encrypted padded text Wa.Message}</enc>
 *   <device-identity>{...}</device-identity>  (if any pkmsg appears)
 * </message>
 * }</pre>
 */
public final class GroupSender {

    private static final Logger LOG = LoggerFactory.getLogger(GroupSender.class);

    private GroupSender() {}

    public record Result(BinaryNode stanza,
                         Map<SignalProtocolAddress, Integer> ciphertextTypes,
                         int skdmRecipients) {}

    /**
     * Build the group-send stanza.
     *
     * @param protocolStore        libsignal store for per-device SessionCipher
     *                             (encrypting the SKDM-only Wa.Message per participant)
     * @param senderKeyStore       libsignal sender-key store backing the GroupCipher
     * @param creds                our auth state (used for me-jid + device-identity child)
     * @param msgId                outbound message id (upper-case hex)
     * @param groupJid             full group JID, e.g. {@code 120363...@g.us}
     * @param participantDeviceJids every group-participant device's JID, ours included —
     *                              we filter out our exact own device
     * @param message              the user-visible payload (gets GroupCipher-encrypted)
     */
    public static Result buildStanza(JaWaProtocolStore protocolStore,
                                     SenderKeyStore senderKeyStore,
                                     AuthCreds creds,
                                     String msgId,
                                     String groupJid,
                                     List<String> participantDeviceJids,
                                     Wa.Message message) {
        Objects.requireNonNull(protocolStore);
        Objects.requireNonNull(senderKeyStore);
        Objects.requireNonNull(message);

        Jid myJid = Jid.parse(creds.meJid);
        if (myJid == null) throw new IllegalStateException("creds.meJid invalid");
        SignalProtocolAddress myAddr = new SignalProtocolAddress(myJid.user(), myJid.device());

        // 1. Pull or create our sender-key state for this group; the returned SKDM
        //    is what every other participant device needs to derive the same chain.
        SenderKeyName senderKeyName = new SenderKeyName(groupJid, myAddr);
        GroupSessionBuilder builder = new GroupSessionBuilder(senderKeyStore);
        SenderKeyDistributionMessage skdm = builder.create(senderKeyName);
        byte[] axoBytes = skdm.serialize();

        // 2. Encrypt the user-visible message via the group cipher into one skmsg.
        byte[] textPadded = MessageEncoder.encode(message);
        byte[] skmsgCiphertext;
        try {
            skmsgCiphertext = new GroupCipher(senderKeyStore, senderKeyName).encrypt(textPadded);
        } catch (NoSessionException e) {
            throw new IllegalStateException("group cipher encrypt failed", e);
        }

        // 3. Build the SKDM-only Wa.Message every participant device will receive via DM.
        Wa.Message skdmMessage = Wa.Message.newBuilder()
            .setSenderKeyDistributionMessage(Wa.Message.SenderKeyDistributionMessage.newBuilder()
                .setGroupId(groupJid)
                .setAxolotlSenderKeyDistributionMessage(ByteString.copyFrom(axoBytes))
                .build())
            .build();
        byte[] skdmPadded = MessageEncoder.encode(skdmMessage);

        // 4. Fan the SKDM-only message out per participant device.
        List<BinaryNode> participants = new ArrayList<>();
        Map<SignalProtocolAddress, Integer> typeMap = new HashMap<>();
        boolean includeDeviceIdentity = false;

        for (String dj : participantDeviceJids) {
            Jid jid = Jid.parse(dj);
            if (jid == null) continue;
            SignalProtocolAddress addr = SessionBootstrap.addressFor(jid);
            if (addr.equals(myAddr)) continue; // skip our own device

            try {
                SessionCipher cipher = new SessionCipher(protocolStore, addr);
                CiphertextMessage ct = cipher.encrypt(skdmPadded);
                String type = ct.getType() == CiphertextMessage.PREKEY_TYPE ? "pkmsg" : "msg";
                if ("pkmsg".equals(type)) includeDeviceIdentity = true;
                typeMap.put(addr, ct.getType());

                BinaryNode enc = new BinaryNode("enc",
                    Map.of("v", "2", "type", type),
                    ct.serialize());
                BinaryNode to = new BinaryNode("to",
                    Map.of("jid", dj),
                    List.of(enc));
                participants.add(to);
            } catch (UntrustedIdentityException e) {
                LOG.warn("SKDM encrypt failed for {}: {}", dj, e.toString());
            }
        }

        // 5. Compose the top-level stanza.
        BinaryNode skmsgEnc = new BinaryNode("enc",
            Map.of("v", "2", "type", "skmsg"),
            skmsgCiphertext);

        List<BinaryNode> outer = new ArrayList<>();
        if (!participants.isEmpty()) {
            outer.add(new BinaryNode("participants", Map.of(), participants));
        }
        outer.add(skmsgEnc);
        if (includeDeviceIdentity && creds.account != null) {
            byte[] deviceIdentity = MessageSender.encodeDeviceIdentityForSend(creds.account);
            outer.add(new BinaryNode("device-identity", Map.of(), deviceIdentity));
        }
        BinaryNode biz = BizNode.buildIfNeeded(message);
        if (biz != null) outer.add(biz);

        BinaryNode stanza = new BinaryNode("message",
            Map.of(
                "to",   groupJid,
                "id",   msgId,
                "type", "text"
            ),
            outer);

        return new Result(stanza, typeMap, participants.size());
    }
}
