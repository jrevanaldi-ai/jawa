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
            @Override public void onQr(java.util.List<String> qrs) {
                if (qrs.isEmpty()) return;
                System.out.println();
                System.out.println(">>> Open WhatsApp → Settings → Linked Devices → Link a Device, then scan:");
                System.out.println();
                System.out.print(id.jawa.util.QrTerminal.render(qrs.get(0)));
                System.out.println();
                System.out.println(">>> ref 1/" + qrs.size() + " — refs rotate every ~30 s; scan within window");
                System.out.println();
            }
            @Override public void onPaired(String jid, String pushName, String platform) {
                System.out.println(">>> PAIRED jid=" + jid + " biz=" + pushName + " platform=" + platform);
                System.out.println(">>> Creds persisted to " + sessionFile.toAbsolutePath());
            }
            @Override public void onConnected() {
                System.out.println(">>> Connected");
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
