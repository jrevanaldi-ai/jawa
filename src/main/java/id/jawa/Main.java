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

        System.out.println("== JaWa " + JaWa.VERSION + " ==");
        System.out.println("Session file: " + sessionFile.toAbsolutePath());

        FileAuthStore store = new FileAuthStore(sessionFile);
        JaWaClient client = new JaWaClient(store);

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
