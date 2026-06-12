// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa;

import id.jawa.core.JaWaClient;
import id.jawa.store.FileAuthStore;

import java.nio.file.Path;

/**
 * Demo runner: connects to WhatsApp Web, prints QR strings on first run,
 * persists session creds, and reconnects without QR on subsequent runs.
 *
 * <p>Usage:
 * <pre>
 *   ./gradlew run -PsessionFile=/path/to/your.session
 * </pre>
 *
 * <p>QR strings printed to stdout in the form
 * {@code ref,base64(noisePub),base64(identityKey),base64(advSecret)} — paste each
 * into any QR generator (or use a lib like ZXing client-side) and scan with WhatsApp.
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        Path sessionFile = Path.of(System.getProperty("jawa.session",
            args.length > 0 ? args[0] : "sessions/default.session"));

        // Derive a per-session Signal-state dir alongside the session file by stripping
        // the .session suffix and appending .signal. Override with -Djawa.signal_dir=...
        String suffix = ".session";
        String basePath = sessionFile.toString();
        String derivedSignalDir = basePath.endsWith(suffix)
            ? basePath.substring(0, basePath.length() - suffix.length()) + ".signal"
            : basePath + ".signal";
        Path signalDir = Path.of(System.getProperty("jawa.signal_dir", derivedSignalDir));

        System.out.println("== JaWa " + JaWa.VERSION + " ==");
        System.out.println("Session file: " + sessionFile.toAbsolutePath());
        System.out.println("Signal dir  : " + signalDir.toAbsolutePath());

        FileAuthStore store = new FileAuthStore(sessionFile);
        JaWaClient client = new JaWaClient(store, signalDir).autoReconnect(false);

        client.listener(new JaWaClient.Listener() {
            @Override public void onPaired(String jid, String pushName, String platform) {
                System.out.println(">>> PAIRED jid=" + jid + " biz=" + pushName + " platform=" + platform);
                System.out.println(">>> Creds persisted to " + sessionFile.toAbsolutePath());
            }
            @Override public void onQr(java.util.List<String> qrs) {
                String phone = System.getProperty("jawa.phone");
                if (phone != null && !phone.isBlank()) {
                    // Switch to pair-code instead of QR
                    client.requestPairingCode(phone, null).whenComplete((code, err) -> {
                        if (err != null) { System.err.println(">>> pair-code request failed: " + err); return; }
                        System.out.println();
                        System.out.println(">>> Pair code: " + code.substring(0, 4) + "-" + code.substring(4));
                        System.out.println(">>> Open WhatsApp → Settings → Linked Devices → Link with phone number → enter this code");
                        System.out.println();
                    });
                    return;
                }
                // No phone supplied — fall through to QR rendering
                if (qrs.isEmpty()) return;
                System.out.println();
                System.out.println(">>> Open WhatsApp → Settings → Linked Devices → Link a Device, then scan:");
                System.out.println();
                System.out.print(id.jawa.util.QrTerminal.render(qrs.get(0)));
                System.out.println();
                System.out.println(">>> ref 1/" + qrs.size() + " — refs rotate every ~30 s; scan within window");
                System.out.println();
            }

            @Override public void onConnected() {
                System.out.println(">>> Connected");
                // Optional: hardcoded interactive demos for live testing native flow types
                // (-Djawa.demo_chat=<jid> -Djawa.demo=mixed_cta|carousel|single_select|send_location|address|quick_reply).
                String demoChat = System.getProperty("jawa.demo_chat");
                String demo = System.getProperty("jawa.demo");
                if (demoChat != null && !demoChat.isBlank() && demo != null) {
                    java.util.List<id.jawa.message.MessageEncoder.CtaButton> mix;
                    switch (demo) {
                        case "mixed_cta" -> {
                            mix = java.util.List.of(
                                id.jawa.message.MessageEncoder.CtaButton.url("🌐 Open repo", "https://github.com/jochris/JaWa"),
                                id.jawa.message.MessageEncoder.CtaButton.copy("📋 Copy code", "JAWA-2026"),
                                id.jawa.message.MessageEncoder.CtaButton.call("📞 Call WA", "+62895416602000"),
                                id.jawa.message.MessageEncoder.CtaButton.quickReply("✨ Quick reply", "qr_hello")
                            );
                            client.sendCtaButtons(demoChat, "Mixed CTA demo 🚀", "JaWa v0.0.3", mix)
                                .whenComplete((id, err) -> System.out.println(err != null ? ">>> err " + err : ">>> Sent mixed_cta id=" + id));
                        }
                        case "single_select" -> {
                            java.util.List<id.jawa.message.MessageEncoder.ListSection> sections = java.util.List.of(
                                new id.jawa.message.MessageEncoder.ListSection("Commands", java.util.List.of(
                                    new id.jawa.message.MessageEncoder.ListRow("ping", "Ping bot", "Cek bot hidup"),
                                    new id.jawa.message.MessageEncoder.ListRow("info", "Server info", "Show server status")
                                )),
                                new id.jawa.message.MessageEncoder.ListSection("Owner", java.util.List.of(
                                    new id.jawa.message.MessageEncoder.ListRow("exec", "Exec shell", "Owner-only")
                                ))
                            );
                            mix = java.util.List.of(
                                id.jawa.message.MessageEncoder.CtaButton.singleSelect("📋 Pick command", sections),
                                id.jawa.message.MessageEncoder.CtaButton.url("📚 Docs", "https://github.com/jochris/JaWa#readme")
                            );
                            client.sendCtaButtons(demoChat, "Single-select + URL demo", "JaWa", mix)
                                .whenComplete((id, err) -> System.out.println(err != null ? ">>> err " + err : ">>> Sent single_select id=" + id));
                        }
                        case "quick_reply" -> {
                            mix = java.util.List.of(
                                id.jawa.message.MessageEncoder.CtaButton.quickReply("✅ Confirm", "qr_yes"),
                                id.jawa.message.MessageEncoder.CtaButton.quickReply("❌ Cancel",  "qr_no")
                            );
                            client.sendCtaButtons(demoChat, "Quick-reply via interactive", null, mix)
                                .whenComplete((id, err) -> System.out.println(err != null ? ">>> err " + err : ">>> Sent quick_reply id=" + id));
                        }
                        case "carousel" -> {
                            java.util.List<id.jawa.message.MessageEncoder.CarouselCard> cards = java.util.List.of(
                                new id.jawa.message.MessageEncoder.CarouselCard("Card 1", "First card body", null, java.util.List.of(
                                    id.jawa.message.MessageEncoder.CtaButton.url("🌐 Visit", "https://github.com/jochris/JaWa")
                                )),
                                new id.jawa.message.MessageEncoder.CarouselCard("Card 2", "Second card body", null, java.util.List.of(
                                    id.jawa.message.MessageEncoder.CtaButton.copy("📋 Copy", "JAWA-CARD2")
                                )),
                                new id.jawa.message.MessageEncoder.CarouselCard("Card 3", "Third card body", null, java.util.List.of(
                                    id.jawa.message.MessageEncoder.CtaButton.quickReply("⭐ Star", "qr_star")
                                ))
                            );
                            client.sendCarousel(demoChat, null, null, cards)
                                .whenComplete((id, err) -> System.out.println(err != null ? ">>> err " + err : ">>> Sent carousel id=" + id));
                        }
                        default -> System.err.println(">>> unknown demo: " + demo);
                    }
                    return;
                }
                // Optional: send CTA buttons (URL / copy / call) via interactiveMessage.
                // _cta format: "url:label:value|copy:label:value|call:label:value"
                String ctaChat = System.getProperty("jawa.cta_chat");
                if (ctaChat != null && !ctaChat.isBlank()) {
                    String body = System.getProperty("jawa.cta_body", "");
                    String footer = System.getProperty("jawa.cta_footer", "");
                    String spec = System.getProperty("jawa.cta", "");
                    java.util.List<id.jawa.message.MessageEncoder.CtaButton> btns = new java.util.ArrayList<>();
                    for (String pair : spec.split("\\|")) {
                        if (pair.isBlank()) continue;
                        String[] parts = pair.split(":", 3);
                        if (parts.length != 3) continue;
                        String kind = parts[0].trim().toLowerCase();
                        String label = parts[1];
                        String value = parts[2];
                        btns.add(switch (kind) {
                            case "url"  -> id.jawa.message.MessageEncoder.CtaButton.url(label, value);
                            case "copy" -> id.jawa.message.MessageEncoder.CtaButton.copy(label, value);
                            case "call" -> id.jawa.message.MessageEncoder.CtaButton.call(label, value);
                            default     -> null;
                        });
                    }
                    btns.removeIf(java.util.Objects::isNull);
                    client.sendCtaButtons(ctaChat, body, footer.isEmpty() ? null : footer, btns)
                        .whenComplete((msgId, err) -> {
                            if (err != null) { System.err.println(">>> cta send failed: " + err); err.printStackTrace(); return; }
                            System.out.println(">>> Sent cta id=" + msgId + " (" + btns.size() + " button(s))");
                        });
                    return;
                }
                // Optional: send a quick-reply buttons message
                // (-Djawa.buttons_chat / _body / _footer / _buttons).
                // _buttons format: "id1:label1|id2:label2|id3:label3" (max 3).
                String buttonsChat = System.getProperty("jawa.buttons_chat");
                if (buttonsChat != null && !buttonsChat.isBlank()) {
                    String body = System.getProperty("jawa.buttons_body", "");
                    String footer = System.getProperty("jawa.buttons_footer", "");
                    String spec = System.getProperty("jawa.buttons", "");
                    java.util.List<id.jawa.message.MessageEncoder.QuickReplyButton> btns = new java.util.ArrayList<>();
                    for (String pair : spec.split("\\|")) {
                        if (pair.isBlank()) continue;
                        String[] parts = pair.split(":", 2);
                        if (parts.length != 2) continue;
                        btns.add(new id.jawa.message.MessageEncoder.QuickReplyButton(parts[0], parts[1]));
                    }
                    client.sendButtonsMessage(buttonsChat, body, footer.isEmpty() ? null : footer, btns)
                        .whenComplete((msgId, err) -> {
                            if (err != null) { System.err.println(">>> buttons send failed: " + err); err.printStackTrace(); return; }
                            System.out.println(">>> Sent buttons id=" + msgId + " (" + btns.size() + " buttons)");
                        });
                    return;
                }
                // Optional: send a list message (-Djawa.list_chat / _title / _body / _footer /
                // _button / _rows). _rows format: "secTitle1>id1=t1|id2=t2|;secTitle2>id3=t3"
                String listChat = System.getProperty("jawa.list_chat");
                if (listChat != null && !listChat.isBlank()) {
                    String title = System.getProperty("jawa.list_title", "");
                    String body = System.getProperty("jawa.list_body", "");
                    String footer = System.getProperty("jawa.list_footer", "");
                    String buttonText = System.getProperty("jawa.list_button", "Select");
                    String rowsSpec = System.getProperty("jawa.list_rows", "");
                    java.util.List<id.jawa.message.MessageEncoder.ListSection> sections = new java.util.ArrayList<>();
                    for (String secSpec : rowsSpec.split(";")) {
                        if (secSpec.isBlank()) continue;
                        String[] hr = secSpec.split(">", 2);
                        String secTitle = hr.length > 1 ? hr[0] : null;
                        String rowsPart = hr.length > 1 ? hr[1] : hr[0];
                        java.util.List<id.jawa.message.MessageEncoder.ListRow> rows = new java.util.ArrayList<>();
                        for (String r : rowsPart.split("\\|")) {
                            if (r.isBlank()) continue;
                            String[] kv = r.split("=", 2);
                            if (kv.length != 2) continue;
                            rows.add(new id.jawa.message.MessageEncoder.ListRow(kv[0], kv[1], null));
                        }
                        sections.add(new id.jawa.message.MessageEncoder.ListSection(secTitle, rows));
                    }
                    client.sendListMessage(listChat, title, body,
                            footer.isEmpty() ? null : footer, buttonText, sections)
                        .whenComplete((msgId, err) -> {
                            if (err != null) { System.err.println(">>> list send failed: " + err); err.printStackTrace(); return; }
                            System.out.println(">>> Sent list id=" + msgId + " (" + sections.size() + " section(s))");
                        });
                    return;
                }
                // Optional: send an image (-Djawa.image_chat / _path / _mimetype / _caption).
                String imageChat = System.getProperty("jawa.image_chat");
                if (imageChat != null && !imageChat.isBlank()) {
                    String imagePath = System.getProperty("jawa.image_path", "");
                    String mimetype = System.getProperty("jawa.image_mimetype", "image/jpeg");
                    String caption = System.getProperty("jawa.image_caption", "");
                    try {
                        byte[] imageBytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(imagePath));
                        client.sendImage(imageChat, imageBytes, mimetype, caption.isEmpty() ? null : caption)
                            .whenComplete((msgId, err) -> {
                                if (err != null) { System.err.println(">>> image send failed: " + err); err.printStackTrace(); return; }
                                System.out.println(">>> Sent image id=" + msgId
                                    + " size=" + imageBytes.length + "B path=" + imagePath);
                            });
                    } catch (java.io.IOException e) {
                        System.err.println(">>> failed to read image: " + e);
                    }
                    return;
                }
                // Optional: edit a previously-sent message (-Djawa.edit_chat / _target_id / _new_text).
                String editChat = System.getProperty("jawa.edit_chat");
                if (editChat != null && !editChat.isBlank()) {
                    String editTargetId = System.getProperty("jawa.edit_target_id", "");
                    String editNewText = System.getProperty("jawa.edit_new_text", "");
                    client.sendEdit(editChat, editTargetId, editNewText).whenComplete((msgId, err) -> {
                        if (err != null) { System.err.println(">>> edit failed: " + err); err.printStackTrace(); return; }
                        System.out.println(">>> Sent edit id=" + msgId + " target=" + editTargetId
                            + " new text=\"" + editNewText + "\"");
                    });
                    return;
                }
                // Optional: revoke / delete-for-everyone (-Djawa.revoke_chat / _target_id / _target_sender / _from_me).
                String revokeChat = System.getProperty("jawa.revoke_chat");
                if (revokeChat != null && !revokeChat.isBlank()) {
                    String revokeTargetId = System.getProperty("jawa.revoke_target_id", "");
                    String revokeTargetSender = System.getProperty("jawa.revoke_target_sender"); // null OK
                    boolean revokeFromMe = "1".equals(System.getProperty("jawa.revoke_from_me", "1"));
                    client.sendRevoke(revokeChat, revokeTargetId, revokeTargetSender, revokeFromMe)
                        .whenComplete((msgId, err) -> {
                            if (err != null) { System.err.println(">>> revoke failed: " + err); err.printStackTrace(); return; }
                            System.out.println(">>> Sent revoke id=" + msgId + " target=" + revokeTargetId);
                        });
                    return;
                }
                // Optional: send a quoted reply (-Djawa.reply_chat / _text / _to_id / _to_sender / _to_text).
                String replyChat = System.getProperty("jawa.reply_chat");
                if (replyChat != null && !replyChat.isBlank()) {
                    String replyText = System.getProperty("jawa.reply_text", "");
                    String replyToId = System.getProperty("jawa.reply_to_id", "");
                    String replyToSender = System.getProperty("jawa.reply_to_sender"); // null OK
                    String replyToText = System.getProperty("jawa.reply_to_text", "");
                    client.sendReply(replyChat, replyText, replyToId, replyToSender, replyToText)
                        .whenComplete((msgId, err) -> {
                            if (err != null) { System.err.println(">>> reply failed: " + err); err.printStackTrace(); return; }
                            System.out.println(">>> Sent reply id=" + msgId + " text=\"" + replyText
                                + "\" quoting id=" + replyToId);
                        });
                    return;
                }
                // Optional: send a reaction (-Djawa.reaction_chat / _target_id / _target_sender / _emoji).
                String reactChat = System.getProperty("jawa.reaction_chat");
                if (reactChat != null && !reactChat.isBlank()) {
                    String reactTargetId = System.getProperty("jawa.reaction_target_id", "");
                    String reactTargetSender = System.getProperty("jawa.reaction_target_sender"); // null OK for DMs
                    boolean reactFromMe = "1".equals(System.getProperty("jawa.reaction_from_me", "0"));
                    String emoji = System.getProperty("jawa.reaction_emoji", "🔥");
                    client.sendReaction(reactChat, reactTargetId, reactTargetSender, reactFromMe, emoji)
                        .whenComplete((msgId, err) -> {
                            if (err != null) { System.err.println(">>> reaction failed: " + err); err.printStackTrace(); return; }
                            System.out.println(">>> Sent reaction id=" + msgId + " emoji=" + emoji
                                + " on target id=" + reactTargetId + " in chat=" + reactChat);
                        });
                    return; // skip default DM send below
                }
                // Optional: send to a specific group (-Djawa.target_group=<full-jid>).
                String targetGroup = System.getProperty("jawa.target_group");
                if (targetGroup != null && !targetGroup.isBlank()) {
                    String text = System.getProperty("jawa.text", "Hello group from JaWa "
                        + java.time.LocalTime.now());
                    client.sendGroupText(targetGroup, text).whenComplete((msgId, err) -> {
                        if (err != null) { System.err.println(">>> group send failed: " + err); err.printStackTrace(); return; }
                        System.out.println(">>> Sent group message id=" + msgId + " text=\"" + text + "\"");
                    });
                    return; // skip the default DM send below
                }
                // Optional: list joined groups (-Djawa.list_groups=1).
                if ("1".equals(System.getProperty("jawa.list_groups"))) {
                    client.queryJoinedGroups().whenComplete((groups, err) -> {
                        if (err != null) { System.err.println(">>> group list failed: " + err); return; }
                        System.out.println(">>> Joined groups (" + groups.size() + "):");
                        for (var g : groups) {
                            System.out.println("    " + g.jid()
                                + "  subject=\"" + g.subject() + "\""
                                + "  participants=" + g.participantJids().size());
                        }
                    });
                }
                // Demo: query devices for me-jid (or another jid via -Djawa.target=...)
                String target = System.getProperty("jawa.target",
                    client.creds() != null ? client.creds().meJid : null);
                if (target == null) return;
                // Strip device suffix — usync expects bare PN jid
                String userOnly = target.contains(":")
                    ? target.substring(0, target.indexOf(':')) + "@" + target.substring(target.indexOf('@') + 1)
                    : target;
                client.queryDevices(java.util.List.of(userOnly)).thenAccept(map -> {
                    System.out.println(">>> Device list for " + userOnly + ":");
                    var list = map.getOrDefault(userOnly, java.util.List.of());
                    if (list.isEmpty()) System.out.println("    (no devices returned)");
                    for (var d : list) {
                        System.out.println("    device id=" + d.id()
                            + " keyIndex=" + d.keyIndex()
                            + (d.hosted() ? " (hosted)" : ""));
                    }
                });
                // Bootstrap Signal sessions for the target's devices
                client.bootstrapSessions(userOnly).whenComplete((addresses, err) -> {
                    if (err != null) { System.err.println(">>> session bootstrap failed: " + err); err.printStackTrace(); return; }
                    System.out.println(">>> Installed " + addresses.size() + " Signal session(s):");
                    for (var a : addresses) System.out.println("    " + a);
                });
                // Send a text — to-self echo by default, override with -Djawa.text=...
                String text = System.getProperty("jawa.text", "Hello from JaWa " + java.time.LocalTime.now());
                client.sendText(userOnly, text).whenComplete((msgId, err) -> {
                    if (err != null) { System.err.println(">>> send failed: " + err); err.printStackTrace(); return; }
                    System.out.println(">>> Sent message id=" + msgId + " body=\"" + text + "\"");
                });
            }
            @Override public void onMessage(id.jawa.message.MessageReceiver.Decoded d) {
                if (d.text() != null) {
                    System.out.println(">>> MESSAGE from=" + d.senderJid()
                        + " id=" + d.msgId()
                        + " text=\"" + d.text() + "\"");
                } else {
                    System.out.println(">>> MESSAGE from=" + d.senderJid()
                        + " id=" + d.msgId()
                        + " (non-text payload encType=" + d.encType() + ")");
                }
            }
            @Override public void onStanza(id.jawa.binary.BinaryNode node) {
                System.out.println("RX: " + node.tag() + " " + node.attrs());
            }
            @Override public void onError(Throwable t) {
                System.err.println(">>> ERROR: " + t);
                t.printStackTrace();
            }
        });

        client.connect();
        client.join();
    }
}
