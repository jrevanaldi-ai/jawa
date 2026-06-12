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
     * Common skeleton shared by every media-message builder. Inlining instead of
     * factoring out keeps each named helper readable as a single block.
     */
    private static com.google.protobuf.ByteString bs(byte[] b) {
        return com.google.protobuf.ByteString.copyFrom(b);
    }

    /** One row in a list-message section — what the user picks from the dropdown. */
    public record ListRow(String rowId, String title, String description) {}

    /** Group of rows under a section header. */
    public record ListSection(String title, java.util.List<ListRow> rows) {}

    /**
     * Build a {@code listMessage} — a dropdown of selectable options grouped into
     * sections. The receiver gets a bubble with body text + a "buttonText" tap target
     * that opens a sheet listing each section's rows.
     *
     * @param title       title shown above the dropdown sheet
     * @param body        main body text in the bubble
     * @param footer      footer caption shown below the body; nullable
     * @param buttonText  the tap target on the bubble that opens the dropdown
     * @param sections    one or more sections, each holding rows
     */
    public static Wa.Message listMessage(String title,
                                         String body,
                                         String footer,
                                         String buttonText,
                                         java.util.List<ListSection> sections) {
        Wa.Message.ListMessage.Builder b = Wa.Message.ListMessage.newBuilder()
            .setTitle(title)
            .setDescription(body)
            .setButtonText(buttonText)
            .setListType(Wa.Message.ListMessage.ListType.SINGLE_SELECT);
        if (footer != null && !footer.isEmpty()) b.setFooterText(footer);
        for (ListSection s : sections) {
            Wa.Message.ListMessage.Section.Builder sb = Wa.Message.ListMessage.Section.newBuilder();
            if (s.title() != null) sb.setTitle(s.title());
            for (ListRow r : s.rows()) {
                Wa.Message.ListMessage.Row.Builder rb = Wa.Message.ListMessage.Row.newBuilder();
                if (r.rowId() != null) rb.setRowId(r.rowId());
                if (r.title() != null) rb.setTitle(r.title());
                if (r.description() != null) rb.setDescription(r.description());
                sb.addRows(rb.build());
            }
            b.addSections(sb.build());
        }
        return Wa.Message.newBuilder().setListMessage(b.build()).build();
    }

    /** Quick-reply button on a {@code buttonsMessage} — id is echoed back when tapped. */
    public record QuickReplyButton(String buttonId, String displayText) {}

    /**
     * Build a {@code buttonsMessage} — up to 3 quick-reply buttons rendered below a
     * body text. When the user taps a button, the recipient gets a
     * {@code buttonsResponseMessage} carrying the {@code buttonId}.
     *
     * @param body     content text shown above the buttons
     * @param footer   footer text; nullable
     * @param buttons  1-3 quick-reply buttons
     */
    public static Wa.Message buttonsMessage(String body, String footer,
                                            java.util.List<QuickReplyButton> buttons) {
        Wa.Message.ButtonsMessage.Builder b = Wa.Message.ButtonsMessage.newBuilder()
            .setContentText(body)
            .setHeaderType(Wa.Message.ButtonsMessage.HeaderType.EMPTY);
        if (footer != null && !footer.isEmpty()) b.setFooterText(footer);
        for (QuickReplyButton qb : buttons) {
            Wa.Message.ButtonsMessage.Button button = Wa.Message.ButtonsMessage.Button.newBuilder()
                .setButtonId(qb.buttonId())
                .setButtonText(Wa.Message.ButtonsMessage.Button.ButtonText.newBuilder()
                    .setDisplayText(qb.displayText())
                    .build())
                .setType(Wa.Message.ButtonsMessage.Button.Type.RESPONSE)
                .build();
            b.addButtons(button);
        }
        return Wa.Message.newBuilder().setButtonsMessage(b.build()).build();
    }

    /**
     * Build a {@code videoMessage} pointing at a freshly-uploaded encrypted video.
     *
     * <p>Pass {@code 0} for unknown {@code seconds} / {@code width} / {@code height} —
     * proto fields stay unset.
     */
    public static Wa.Message videoMessage(
            String uploadedUrl, String directPath,
            byte[] mediaKey, byte[] fileSha256, byte[] fileEncSha256,
            long fileLength, String mimetype,
            String caption, int seconds, int width, int height) {
        Wa.Message.VideoMessage.Builder b = Wa.Message.VideoMessage.newBuilder()
            .setUrl(uploadedUrl)
            .setDirectPath(directPath)
            .setMediaKey(bs(mediaKey))
            .setFileSha256(bs(fileSha256))
            .setFileEncSha256(bs(fileEncSha256))
            .setFileLength(fileLength)
            .setMimetype(mimetype)
            .setMediaKeyTimestamp(System.currentTimeMillis() / 1000);
        if (caption != null && !caption.isEmpty()) b.setCaption(caption);
        if (seconds > 0) b.setSeconds(seconds);
        if (width > 0)   b.setWidth(width);
        if (height > 0)  b.setHeight(height);
        return Wa.Message.newBuilder().setVideoMessage(b.build()).build();
    }

    /**
     * Build an {@code audioMessage} pointing at a freshly-uploaded encrypted audio
     * file. Set {@code ptt=true} for voice notes (push-to-talk), {@code false} for
     * regular audio attachments.
     */
    public static Wa.Message audioMessage(
            String uploadedUrl, String directPath,
            byte[] mediaKey, byte[] fileSha256, byte[] fileEncSha256,
            long fileLength, String mimetype,
            int seconds, boolean ptt) {
        Wa.Message.AudioMessage.Builder b = Wa.Message.AudioMessage.newBuilder()
            .setUrl(uploadedUrl)
            .setDirectPath(directPath)
            .setMediaKey(bs(mediaKey))
            .setFileSha256(bs(fileSha256))
            .setFileEncSha256(bs(fileEncSha256))
            .setFileLength(fileLength)
            .setMimetype(mimetype)
            .setPtt(ptt)
            .setMediaKeyTimestamp(System.currentTimeMillis() / 1000);
        if (seconds > 0) b.setSeconds(seconds);
        return Wa.Message.newBuilder().setAudioMessage(b.build()).build();
    }

    /**
     * Build a {@code documentMessage} pointing at a freshly-uploaded encrypted
     * document. {@code fileName} shows up in the recipient's chat as the document
     * label; {@code title} (optional) is a richer display title some clients render.
     */
    public static Wa.Message documentMessage(
            String uploadedUrl, String directPath,
            byte[] mediaKey, byte[] fileSha256, byte[] fileEncSha256,
            long fileLength, String mimetype,
            String fileName, String title) {
        Wa.Message.DocumentMessage.Builder b = Wa.Message.DocumentMessage.newBuilder()
            .setUrl(uploadedUrl)
            .setDirectPath(directPath)
            .setMediaKey(bs(mediaKey))
            .setFileSha256(bs(fileSha256))
            .setFileEncSha256(bs(fileEncSha256))
            .setFileLength(fileLength)
            .setMimetype(mimetype)
            .setMediaKeyTimestamp(System.currentTimeMillis() / 1000);
        if (fileName != null && !fileName.isEmpty()) b.setFileName(fileName);
        if (title != null && !title.isEmpty())       b.setTitle(title);
        return Wa.Message.newBuilder().setDocumentMessage(b.build()).build();
    }

    /**
     * Build an {@code imageMessage} pointing at a freshly-uploaded encrypted image.
     *
     * @param uploadedUrl    the {@code url} from the media upload response
     * @param directPath     the {@code direct_path} from the media upload response
     * @param mediaKey       the 32-byte media key used to encrypt the image (recipient
     *                       needs this to derive the AES-CBC + HMAC keys)
     * @param fileSha256     SHA-256 over the plaintext bytes
     * @param fileEncSha256  SHA-256 over the ciphertext + 10-byte MAC bytes
     * @param fileLength     plaintext byte length
     * @param mimetype       e.g. {@code "image/jpeg"}
     * @param caption        optional caption shown under the image; pass {@code null}
     *                       or empty for no caption
     */
    public static Wa.Message imageMessage(
            String uploadedUrl,
            String directPath,
            byte[] mediaKey,
            byte[] fileSha256,
            byte[] fileEncSha256,
            long fileLength,
            String mimetype,
            String caption) {
        Wa.Message.ImageMessage.Builder b = Wa.Message.ImageMessage.newBuilder()
            .setUrl(uploadedUrl)
            .setDirectPath(directPath)
            .setMediaKey(com.google.protobuf.ByteString.copyFrom(mediaKey))
            .setFileSha256(com.google.protobuf.ByteString.copyFrom(fileSha256))
            .setFileEncSha256(com.google.protobuf.ByteString.copyFrom(fileEncSha256))
            .setFileLength(fileLength)
            .setMimetype(mimetype)
            .setMediaKeyTimestamp(System.currentTimeMillis() / 1000);
        if (caption != null && !caption.isEmpty()) b.setCaption(caption);
        return Wa.Message.newBuilder().setImageMessage(b.build()).build();
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
