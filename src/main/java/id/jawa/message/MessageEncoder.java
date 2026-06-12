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
     * Build a poll. Selectable-count {@code 1} produces a single-select poll
     * ({@code pollCreationMessageV3}); higher values produce multi-select
     * ({@code pollCreationMessage}). Pass {@code 0} for "no upper bound" semantics
     * (server treats it as unlimited).
     *
     * <p>A fresh random 32-byte {@code messageSecret} (the {@code encKey}) is
     * generated and ridden in {@code messageContextInfo} — voters use it to
     * encrypt their own vote selections so even the server can't see them.
     */
    public static Wa.Message pollMessage(String name,
                                         java.util.List<String> options,
                                         int selectableCount) {
        Wa.Message.PollCreationMessage.Builder pcm = Wa.Message.PollCreationMessage.newBuilder()
            .setName(name)
            .setSelectableOptionsCount(selectableCount);
        for (String opt : options) {
            pcm.addOptions(Wa.Message.PollCreationMessage.Option.newBuilder()
                .setOptionName(opt).build());
        }
        Wa.MessageContextInfo mci = Wa.MessageContextInfo.newBuilder()
            .setMessageSecret(com.google.protobuf.ByteString.copyFrom(id.jawa.util.Bytes.random(32)))
            .build();
        Wa.Message.Builder mb = Wa.Message.newBuilder().setMessageContextInfo(mci);
        if (selectableCount == 1) {
            mb.setPollCreationMessageV3(pcm.build());
        } else {
            mb.setPollCreationMessage(pcm.build());
        }
        return mb.build();
    }

    /**
     * Build a text message that tags one or more users. The receiver's app renders the
     * mention as a tappable handle that opens the mentioned user's profile.
     *
     * <p>Required shape: the {@code body} text must already contain {@code @<number>}
     * for each mention (e.g. {@code "halo @628xxx"}); {@code mentionedBareJids} must
     * list the full bare JIDs in matching order (e.g. {@code "628xxx@s.whatsapp.net"}).
     * WhatsApp uses the JID list to know who to notify and the {@code @<number>} in
     * the body to know where to render the highlight.
     */
    public static Wa.Message textWithMentions(String body, java.util.List<String> mentionedBareJids) {
        Wa.ContextInfo.Builder ctx = Wa.ContextInfo.newBuilder();
        for (String jid : mentionedBareJids) ctx.addMentionedJid(jid);
        Wa.Message.ExtendedTextMessage extended = Wa.Message.ExtendedTextMessage.newBuilder()
            .setText(body)
            .setContextInfo(ctx.build())
            .build();
        return Wa.Message.newBuilder().setExtendedTextMessage(extended).build();
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

    /**
     * Call-to-action button on an {@code interactiveMessage.nativeFlowMessage}.
     * Three variants exposed by {@link #url}, {@link #copy}, {@link #call} factories;
     * use raw constructor only for protocol experimentation.
     */
    public record CtaButton(String name, String paramsJson) {
        /** URL button — taps open {@code url} in the user's browser. */
        public static CtaButton url(String displayText, String url) {
            return new CtaButton("cta_url",
                "{\"display_text\":\"" + escape(displayText)
              + "\",\"url\":\"" + escape(url)
              + "\",\"merchant_url\":\"" + escape(url) + "\"}");
        }
        /** Copy button — taps copy {@code copyCode} to clipboard. */
        public static CtaButton copy(String displayText, String copyCode) {
            return new CtaButton("cta_copy",
                "{\"display_text\":\"" + escape(displayText)
              + "\",\"copy_code\":\"" + escape(copyCode) + "\"}");
        }
        /** Call button — taps dial {@code phoneNumber}. Phone must be E.164 with leading +. */
        public static CtaButton call(String displayText, String phoneNumber) {
            return new CtaButton("cta_call",
                "{\"display_text\":\"" + escape(displayText)
              + "\",\"phone_number\":\"" + escape(phoneNumber) + "\"}");
        }

        /** Quick-reply button inside native flow — fires an inbound text with {@code id}. */
        public static CtaButton quickReply(String displayText, String id) {
            return new CtaButton("quick_reply",
                "{\"display_text\":\"" + escape(displayText)
              + "\",\"id\":\"" + escape(id) + "\"}");
        }

        /**
         * Single-select button — taps open a sheet listing the given sections / rows.
         * Same UX as a ListMessage but rides inside the broader InteractiveMessage so
         * it can be mixed with URL / copy / call buttons in the same bubble.
         */
        public static CtaButton singleSelect(String displayText, java.util.List<ListSection> sections) {
            StringBuilder b = new StringBuilder("{\"title\":\"").append(escape(displayText));
            b.append("\",\"sections\":[");
            for (int i = 0; i < sections.size(); i++) {
                if (i > 0) b.append(",");
                ListSection s = sections.get(i);
                b.append("{\"title\":\"").append(escape(s.title() == null ? "" : s.title()));
                b.append("\",\"rows\":[");
                for (int j = 0; j < s.rows().size(); j++) {
                    if (j > 0) b.append(",");
                    ListRow r = s.rows().get(j);
                    b.append("{\"id\":\"").append(escape(r.rowId() == null ? "" : r.rowId()));
                    b.append("\",\"title\":\"").append(escape(r.title() == null ? "" : r.title()));
                    b.append("\",\"description\":\"").append(escape(r.description() == null ? "" : r.description()));
                    b.append("\"}");
                }
                b.append("]}");
            }
            b.append("]}");
            return new CtaButton("single_select", b.toString());
        }
    }

    /**
     * One card inside a {@link #interactiveCarousel} bubble. Each card has its own
     * body text + native-flow buttons; cards scroll horizontally on the receiver UI.
     */
    public record CarouselCard(String title, String body, String footer,
                               java.util.List<CtaButton> buttons) {}

    /**
     * Build an {@code interactiveMessage.carouselMessage} — horizontally-scrollable
     * cards, each with its own body + button set.
     *
     * <p><b>Note:</b> WhatsApp's app requires each card to carry an {@code imageMessage} /
     * {@code videoMessage} / {@code productMessage} header to render — pure-text cards
     * are silently dropped as "Unsupported message". Wiring an M8 media upload into the
     * card header is open work; until then this builder produces a stanza the server
     * accepts but the receiver app won't render.
     */
    public static Wa.Message interactiveCarousel(String body, String footer,
                                                 java.util.List<CarouselCard> cards) {
        Wa.Message.InteractiveMessage.CarouselMessage.Builder cm =
            Wa.Message.InteractiveMessage.CarouselMessage.newBuilder()
                .setMessageVersion(1);
        for (CarouselCard card : cards) {
            Wa.Message.InteractiveMessage.NativeFlowMessage.Builder nf =
                Wa.Message.InteractiveMessage.NativeFlowMessage.newBuilder()
                    .setMessageVersion(1);
            for (CtaButton btn : card.buttons()) {
                nf.addButtons(Wa.Message.InteractiveMessage.NativeFlowMessage.NativeFlowButton.newBuilder()
                    .setName(btn.name())
                    .setButtonParamsJson(btn.paramsJson())
                    .build());
            }
            Wa.Message.InteractiveMessage.Builder cardMsg = Wa.Message.InteractiveMessage.newBuilder()
                .setBody(Wa.Message.InteractiveMessage.Body.newBuilder()
                    .setText(card.body() == null ? "" : card.body())
                    .build())
                .setNativeFlowMessage(nf.build());
            if (card.title() != null && !card.title().isEmpty()) {
                cardMsg.setHeader(Wa.Message.InteractiveMessage.Header.newBuilder()
                    .setTitle(card.title())
                    .build());
            }
            if (card.footer() != null && !card.footer().isEmpty()) {
                cardMsg.setFooter(Wa.Message.InteractiveMessage.Footer.newBuilder()
                    .setText(card.footer()).build());
            }
            cm.addCards(cardMsg.build());
        }
        Wa.Message.InteractiveMessage.Builder im = Wa.Message.InteractiveMessage.newBuilder()
            .setCarouselMessage(cm.build());
        if (body != null && !body.isEmpty()) {
            im.setBody(Wa.Message.InteractiveMessage.Body.newBuilder().setText(body).build());
        }
        if (footer != null && !footer.isEmpty()) {
            im.setFooter(Wa.Message.InteractiveMessage.Footer.newBuilder().setText(footer).build());
        }
        return Wa.Message.newBuilder().setInteractiveMessage(im.build()).build();
    }

    /**
     * Build an {@code interactiveMessage} with native-flow CTA buttons (URL / copy /
     * call). The {@code <biz>} stanza wrap added by {@link MessageSender} /
     * {@link GroupSender} is what makes the receiver app actually render the buttons.
     */
    public static Wa.Message interactiveCtaButtons(String body, String footer,
                                                   java.util.List<CtaButton> buttons) {
        Wa.Message.InteractiveMessage.NativeFlowMessage.Builder nf =
            Wa.Message.InteractiveMessage.NativeFlowMessage.newBuilder()
                .setMessageVersion(1);
        for (CtaButton b : buttons) {
            nf.addButtons(Wa.Message.InteractiveMessage.NativeFlowMessage.NativeFlowButton.newBuilder()
                .setName(b.name())
                .setButtonParamsJson(b.paramsJson())
                .build());
        }
        Wa.Message.InteractiveMessage.Builder im = Wa.Message.InteractiveMessage.newBuilder()
            .setBody(Wa.Message.InteractiveMessage.Body.newBuilder().setText(body).build())
            .setNativeFlowMessage(nf.build());
        if (footer != null && !footer.isEmpty()) {
            im.setFooter(Wa.Message.InteractiveMessage.Footer.newBuilder().setText(footer).build());
        }
        return Wa.Message.newBuilder().setInteractiveMessage(im.build()).build();
    }

    /** Minimal JSON string escape — handles the characters whatsapp's nativeFlow params can carry. */
    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default   -> b.append(c);
            }
        }
        return b.toString();
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
