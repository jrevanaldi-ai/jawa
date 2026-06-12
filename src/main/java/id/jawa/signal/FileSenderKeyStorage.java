// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.signal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.groups.SenderKeyName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

/**
 * Persistent backing for libsignal {@link org.whispersystems.libsignal.groups.state.SenderKeyRecord}s.
 * One file per (group, sender device) pair under {@code <baseDir>/}, named
 * {@code base64url(groupId)__base64url(senderName)__<deviceId>.senderkey}.
 *
 * <p>Without this, every restart wipes our sender-chain state for every group we've
 * sent to. The next outbound group message rebuilds a fresh sender-key and forces
 * re-distribution of the SKDM to every participant device (~hundreds of `pkmsg`
 * encryptions for a busy group).
 */
public final class FileSenderKeyStorage {

    private static final Logger LOG = LoggerFactory.getLogger(FileSenderKeyStorage.class);
    private static final String SUFFIX = ".senderkey";

    private final Path baseDir;
    private final ConcurrentMap<SenderKeyName, byte[]> cache = new ConcurrentHashMap<>();

    public FileSenderKeyStorage(Path baseDir) {
        this.baseDir = baseDir;
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create sender-key dir " + baseDir, e);
        }
        loadAll();
    }

    private void loadAll() {
        if (!Files.isDirectory(baseDir)) return;
        try (Stream<Path> files = Files.list(baseDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(SUFFIX))
                .forEach(this::loadOne);
        } catch (IOException e) {
            LOG.warn("Failed listing sender-key dir {}: {}", baseDir, e.toString());
        }
        LOG.info("Loaded {} sender-key record(s) from {}", cache.size(), baseDir);
    }

    private void loadOne(Path file) {
        SenderKeyName name = decodeFilename(file.getFileName().toString());
        if (name == null) return;
        try {
            cache.put(name, Files.readAllBytes(file));
        } catch (IOException e) {
            LOG.warn("Failed reading sender-key {}: {}", file, e.toString());
        }
    }

    public byte[] get(SenderKeyName name) { return cache.get(name); }

    public void put(SenderKeyName name, byte[] serialized) {
        cache.put(name, serialized);
        try {
            String filename = encodeFilename(name);
            Path tmp = baseDir.resolve(filename + ".tmp");
            Path dest = baseDir.resolve(filename);
            Files.write(tmp, serialized);
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.warn("Failed persisting sender-key for {}: {}", name, e.toString());
        }
    }

    public boolean contains(SenderKeyName name) {
        byte[] s = cache.get(name);
        return s != null && s.length > 0;
    }

    public int size() { return cache.size(); }

    private static String encodeFilename(SenderKeyName name) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String group = enc.encodeToString(name.getGroupId().getBytes(StandardCharsets.UTF_8));
        SignalProtocolAddress addr = name.getSender();
        String sender = enc.encodeToString(addr.getName().getBytes(StandardCharsets.UTF_8));
        return group + "__" + sender + "__" + addr.getDeviceId() + SUFFIX;
    }

    private static SenderKeyName decodeFilename(String filename) {
        if (!filename.endsWith(SUFFIX)) return null;
        String base = filename.substring(0, filename.length() - SUFFIX.length());
        String[] parts = base.split("__");
        if (parts.length != 3) return null;
        try {
            Base64.Decoder dec = Base64.getUrlDecoder();
            String groupId = new String(dec.decode(parts[0]), StandardCharsets.UTF_8);
            String senderName = new String(dec.decode(parts[1]), StandardCharsets.UTF_8);
            int deviceId = Integer.parseInt(parts[2]);
            return new SenderKeyName(groupId, new SignalProtocolAddress(senderName, deviceId));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
