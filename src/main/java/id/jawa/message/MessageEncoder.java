// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.message;

import id.jawa.proto.Wa;
import id.jawa.util.Bytes;

/**
 * Encode a {@link id.jawa.proto.Wa.Message} for E2E transmission and decode it back.
 *
 * <p>WhatsApp wraps every message proto with a random-pad-max-16 scheme before
 * Signal encryption: append {@code N} bytes, each equal to {@code N}, where {@code N}
 * is uniformly random in {@code [1, 16]}.
 *
 * <p>This mirrors {@code writeRandomPadMax16} / {@code unpadRandomMax16} in Baileys'
 * {@code Utils/generics.ts}.
 */
public final class MessageEncoder {

    private MessageEncoder() {}

    /** Serialise {@code msg} and append random padding. */
    public static byte[] encode(Wa.Message msg) {
        byte[] body = msg.toByteArray();
        return pad(body);
    }

    /** Strip the trailing pad bytes from a decrypted message. */
    public static byte[] unpad(byte[] padded) {
        if (padded == null || padded.length == 0) {
            throw new IllegalArgumentException("empty payload");
        }
        int n = padded[padded.length - 1] & 0xFF;
        if (n == 0 || n > padded.length || n > 16) {
            throw new IllegalArgumentException("invalid pad length: " + n);
        }
        return Bytes.slice(padded, 0, padded.length - n);
    }

    /** Append a random pad in {@code [1, 16]} bytes, each containing the pad length. */
    public static byte[] pad(byte[] body) {
        int n = (Bytes.random(1)[0] & 0x0F) + 1; // 1..16
        byte[] out = new byte[body.length + n];
        System.arraycopy(body, 0, out, 0, body.length);
        byte pad = (byte) n;
        for (int i = body.length; i < out.length; i++) out[i] = pad;
        return out;
    }

    /** Build a plain text {@code Wa.Message} with the given conversation string. */
    public static Wa.Message text(String body) {
        return Wa.Message.newBuilder().setConversation(body).build();
    }

    /**
     * Build a reaction {@code Wa.Message} pointing at a target message.
     *
     * @param chatJid         the chat where the target message lives (group JID
     *                        or peer's bare JID for DMs)
     * @param targetMsgId     the original message's {@code id} attribute
     * @param targetParticipant for group reactions, the device JID of the original
     *                        sender. For DM reactions on a message sent by the peer,
     *                        leave {@code null}. For DM reactions on our own message
     *                        also {@code null}.
     * @param fromMe          {@code true} if the target message was sent by us
     * @param emoji           the reaction text — typically a single emoji. Pass
     *                        an empty string to remove an existing reaction.
     */
    /**
     * Build an {@code extendedTextMessage} reply that quotes an existing message.
     *
     * <p>The {@code quotedMessage} stub is enough for WhatsApp to render the block-quote
     * preview above the reply text — it does not need the full original content, just
     * the conversation field is sufficient.
     *
     * @param text          the new reply text
     * @param quotedMsgId   the original message's id
     * @param quotedSender  the original sender's JID. For group replies, this is the
     *                      sender's device JID (e.g. {@code 6289...@lid}); for DMs, null
     * @param quotedText    a short preview of the original text (used to render the
     *                      block-quote). Pass the same text that was decoded — if null
     *                      or empty, an empty conversation stub is used
     */
    public static Wa.Message reply(String text,
                                   String quotedMsgId,
                                   String quotedSender,
                                   String quotedText) {
        Wa.Message quotedStub = Wa.Message.newBuilder()
            .setConversation(quotedText == null ? "" : quotedText)
            .build();
        Wa.ContextInfo.Builder ctx = Wa.ContextInfo.newBuilder()
            .setStanzaId(quotedMsgId)
            .setQuotedMessage(quotedStub);
        if (quotedSender != null && !quotedSender.isBlank()) {
            ctx.setParticipant(quotedSender);
        }
        Wa.Message.ExtendedTextMessage extended = Wa.Message.ExtendedTextMessage.newBuilder()
            .setText(text)
            .setContextInfo(ctx.build())
            .build();
        return Wa.Message.newBuilder().setExtendedTextMessage(extended).build();
    }

    /**
     * Build an edit envelope for an existing message. The recipient's UI replaces the
     * original text with {@code newContent} (subject to ~15-minute edit window).
     *
     * <p>Shape: {@code Wa.Message.editedMessage = FutureProofMessage{Wa.Message{
     * protocolMessage{key{fromMe=true, id=targetMsgId, remoteJid=chatJid},
     * type=MESSAGE_EDIT, editedMessage=newContent, timestampMs=now}}}}.
     *
     * @param chatJid       chat where the original lives
     * @param targetMsgId   id of the message to edit
     * @param newContent    the replacement {@code Wa.Message} (typically a text built
     *                      with {@link #text})
     * @param timestampMs   server-clock-ish timestamp of the edit (ms since epoch)
     */
    public static Wa.Message edit(String chatJid,
                                  String targetMsgId,
                                  Wa.Message newContent,
                                  long timestampMs) {
        Wa.MessageKey key = Wa.MessageKey.newBuilder()
            .setFromMe(true)
            .setId(targetMsgId)
            .setRemoteJid(chatJid)
            .build();
        Wa.Message.ProtocolMessage protocol = Wa.Message.ProtocolMessage.newBuilder()
            .setKey(key)
            .setType(Wa.Message.ProtocolMessage.Type.MESSAGE_EDIT)
            .setEditedMessage(newContent)
            .setTimestampMs(timestampMs)
            .build();
        Wa.Message inner = Wa.Message.newBuilder().setProtocolMessage(protocol).build();
        Wa.Message.FutureProofMessage wrap = Wa.Message.FutureProofMessage.newBuilder()
            .setMessage(inner)
            .build();
        return Wa.Message.newBuilder().setEditedMessage(wrap).build();
    }

    /**
     * Build a "delete for everyone" (revoke) envelope.
     *
     * <p>Shape: {@code Wa.Message.protocolMessage{key{fromMe, id, remoteJid,
     * participant}, type=REVOKE}}.
     *
     * @param chatJid           chat where the original lives
     * @param targetMsgId       id of the message to revoke
     * @param targetParticipant for group revokes of someone else's message (you are
     *                          admin), the sender's device JID; {@code null} when
     *                          revoking your own
     * @param fromMe            {@code true} if the target was sent by us
     */
    public static Wa.Message revoke(String chatJid,
                                    String targetMsgId,
                                    String targetParticipant,
                                    boolean fromMe) {
        Wa.MessageKey.Builder key = Wa.MessageKey.newBuilder()
            .setRemoteJid(chatJid)
            .setFromMe(fromMe)
            .setId(targetMsgId);
        if (targetParticipant != null && !targetParticipant.isBlank()) {
            key.setParticipant(targetParticipant);
        }
        Wa.Message.ProtocolMessage protocol = Wa.Message.ProtocolMessage.newBuilder()
            .setKey(key.build())
            .setType(Wa.Message.ProtocolMessage.Type.REVOKE)
            .build();
        return Wa.Message.newBuilder().setProtocolMessage(protocol).build();
    }

    public static Wa.Message reaction(String chatJid,
                                      String targetMsgId,
                                      String targetParticipant,
                                      boolean fromMe,
                                      String emoji,
                                      long timestampMs) {
        Wa.MessageKey.Builder key = Wa.MessageKey.newBuilder()
            .setRemoteJid(chatJid)
            .setFromMe(fromMe)
            .setId(targetMsgId);
        if (targetParticipant != null) key.setParticipant(targetParticipant);

        Wa.Message.ReactionMessage reaction = Wa.Message.ReactionMessage.newBuilder()
            .setKey(key.build())
            .setText(emoji == null ? "" : emoji)
            .setSenderTimestampMs(timestampMs)
            .build();
        return Wa.Message.newBuilder().setReactionMessage(reaction).build();
    }

    /**
     * Wrap {@code inner} in a {@code DeviceSentMessage} envelope addressed to
     * {@code destinationJid}. Used when a companion device sends a message that should
     * also appear on the user's other own devices — the phone routes the inner message
     * to the chat identified by {@code destinationJid}.
     *
     * <p>Without this envelope, the phone Signal-decrypts successfully but has no chat
     * to attribute the message to, so it silently drops the payload (server still ACKs).
     */
    public static Wa.Message deviceSent(String destinationJid, Wa.Message inner) {
        return Wa.Message.newBuilder()
            .setDeviceSentMessage(Wa.Message.DeviceSentMessage.newBuilder()
                .setDestinationJid(destinationJid)
                .setMessage(inner)
                .build())
            .build();
    }
}
