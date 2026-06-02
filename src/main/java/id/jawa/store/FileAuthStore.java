// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.store;

import id.jawa.util.Bytes;
import id.jawa.util.crypto.KeyPair25519;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Simple key=value file persistence for {@link AuthCreds}.
 *
 * <p>One file per session. Byte arrays serialize as base64. Missing fields decode as
 * {@code null} so partially-paired files load gracefully.
 *
 * <p>This is a reference implementation — production users should plug in their own
 * {@link AuthStore} backed by SQLite, an encrypted keystore, or whatever fits.
 */
public final class FileAuthStore implements AuthStore {

    private final Path file;

    public FileAuthStore(Path file) { this.file = file; }

    @Override
    public AuthCreds load() throws IOException {
        if (!Files.exists(file)) return null;
        Map<String, String> kv = readKv();

        AuthCreds c = new AuthCreds();
        c.noiseKey = new KeyPair25519(b64(kv.get("noiseKey.priv")), b64(kv.get("noiseKey.pub")));
        c.signedIdentityKey = new KeyPair25519(
            b64(kv.get("identityKey.priv")), b64(kv.get("identityKey.pub")));
        c.signedPreKey = new SignedPreKey(
            Integer.parseInt(kv.get("signedPreKey.id")),
            new KeyPair25519(b64(kv.get("signedPreKey.priv")), b64(kv.get("signedPreKey.pub"))),
            b64(kv.get("signedPreKey.sig")));
        c.registrationId   = Integer.parseInt(kv.get("registrationId"));
        c.advSecretKey     = b64(kv.get("advSecretKey"));
        c.nextPreKeyId     = parseIntOrDefault(kv.get("nextPreKeyId"), 1);
        c.firstUnuploadedPreKeyId = parseIntOrDefault(kv.get("firstUnuploadedPreKeyId"), 1);
        c.accountSyncCounter      = parseIntOrDefault(kv.get("accountSyncCounter"), 0);
        c.account  = b64Opt(kv.get("account"));
        c.meJid    = kv.get("meJid");
        c.meLid    = kv.get("meLid");
        c.pushName = kv.get("pushName");
        c.platform = kv.get("platform");
        return c;
    }

    @Override
    public void save(AuthCreds c) throws IOException {
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put("noiseKey.priv",     Bytes.toBase64(c.noiseKey.privateKey()));
        kv.put("noiseKey.pub",      Bytes.toBase64(c.noiseKey.publicKey()));
        kv.put("identityKey.priv",  Bytes.toBase64(c.signedIdentityKey.privateKey()));
        kv.put("identityKey.pub",   Bytes.toBase64(c.signedIdentityKey.publicKey()));
        kv.put("signedPreKey.id",   Integer.toString(c.signedPreKey.keyId()));
        kv.put("signedPreKey.priv", Bytes.toBase64(c.signedPreKey.keyPair().privateKey()));
        kv.put("signedPreKey.pub",  Bytes.toBase64(c.signedPreKey.keyPair().publicKey()));
        kv.put("signedPreKey.sig",  Bytes.toBase64(c.signedPreKey.signature()));
        kv.put("registrationId",    Integer.toString(c.registrationId));
        kv.put("advSecretKey",      Bytes.toBase64(c.advSecretKey));
        kv.put("nextPreKeyId",      Integer.toString(c.nextPreKeyId));
        kv.put("firstUnuploadedPreKeyId", Integer.toString(c.firstUnuploadedPreKeyId));
        kv.put("accountSyncCounter", Integer.toString(c.accountSyncCounter));
        if (c.account  != null) kv.put("account",  Bytes.toBase64(c.account));
        if (c.meJid    != null) kv.put("meJid",    c.meJid);
        if (c.meLid    != null) kv.put("meLid",    c.meLid);
        if (c.pushName != null) kv.put("pushName", c.pushName);
        if (c.platform != null) kv.put("platform", c.platform);
        writeKv(kv);
    }

    @Override
    public boolean isPaired() throws IOException {
        AuthCreds c = load();
        return c != null && c.account != null && c.meJid != null;
    }

    private Map<String, String> readKv() throws IOException {
        Map<String, String> kv = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            kv.put(line.substring(0, eq), line.substring(eq + 1));
        }
        return kv;
    }

    private void writeKv(Map<String, String> kv) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        StringBuilder sb = new StringBuilder(kv.size() * 80);
        sb.append("# JaWa session — KEEP PRIVATE\n");
        for (var e : kv.entrySet()) sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, sb.toString());
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private static byte[] b64(String s)    { return Bytes.fromBase64(s); }
    private static byte[] b64Opt(String s) { return s == null ? null : Bytes.fromBase64(s); }
    private static int parseIntOrDefault(String s, int d) {
        return s == null ? d : Integer.parseInt(s);
    }
}
