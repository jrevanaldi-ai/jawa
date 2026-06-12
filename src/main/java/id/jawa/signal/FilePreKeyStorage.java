// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.signal;

import id.jawa.util.crypto.KeyPair25519;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

/**
 * Persistent backing for the JaWa-side one-time pre-key store. One file per pre-key
 * under {@code <baseDir>/}, named {@code <id>.prekey}. Each file is exactly 64 bytes:
 * 32 bytes private key followed by 32 bytes public key.
 *
 * <p>Without persistence, pre-keys generated and uploaded to the server in one run
 * are unrecoverable after restart. Peers that fetched a bundle and encrypted using a
 * since-lost pre-key id would hit decrypt failures forever — the only recovery would
 * be retry-receipt with a fresh upload.
 */
public final class FilePreKeyStorage {

    private static final Logger LOG = LoggerFactory.getLogger(FilePreKeyStorage.class);

    private static final String SUFFIX = ".prekey";
    private static final int KEY_BYTES = 32;
    private static final int RECORD_BYTES = KEY_BYTES * 2;

    private final Path baseDir;
    private final ConcurrentMap<Integer, KeyPair25519> cache = new ConcurrentHashMap<>();

    public FilePreKeyStorage(Path baseDir) {
        this.baseDir = baseDir;
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create pre-key dir " + baseDir, e);
        }
        loadAll();
    }

    private void loadAll() {
        if (!Files.isDirectory(baseDir)) return;
        try (Stream<Path> files = Files.list(baseDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(SUFFIX))
                .forEach(this::loadOne);
        } catch (IOException e) {
            LOG.warn("Failed listing pre-key dir {}: {}", baseDir, e.toString());
        }
        LOG.info("Loaded {} pre-key(s) from {}", cache.size(), baseDir);
    }

    private void loadOne(Path file) {
        String name = file.getFileName().toString();
        String idStr = name.substring(0, name.length() - SUFFIX.length());
        int id;
        try { id = Integer.parseInt(idStr); }
        catch (NumberFormatException e) { return; }
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length != RECORD_BYTES) {
                LOG.warn("Pre-key file {} has wrong length {} (expected {})", file, bytes.length, RECORD_BYTES);
                return;
            }
            byte[] priv = new byte[KEY_BYTES];
            byte[] pub  = new byte[KEY_BYTES];
            System.arraycopy(bytes, 0, priv, 0, KEY_BYTES);
            System.arraycopy(bytes, KEY_BYTES, pub, 0, KEY_BYTES);
            cache.put(id, new KeyPair25519(priv, pub));
        } catch (IOException e) {
            LOG.warn("Failed reading pre-key {}: {}", file, e.toString());
        }
    }

    public KeyPair25519 get(int id) { return cache.get(id); }

    public void put(int id, KeyPair25519 kp) {
        cache.put(id, kp);
        try {
            byte[] bytes = new byte[RECORD_BYTES];
            System.arraycopy(kp.privateKey(), 0, bytes, 0, KEY_BYTES);
            System.arraycopy(kp.publicKey(),  0, bytes, KEY_BYTES, KEY_BYTES);
            Path tmp = baseDir.resolve(id + SUFFIX + ".tmp");
            Path dest = baseDir.resolve(id + SUFFIX);
            Files.write(tmp, bytes);
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.warn("Failed persisting pre-key {}: {}", id, e.toString());
        }
    }

    public void remove(int id) {
        cache.remove(id);
        try {
            Files.deleteIfExists(baseDir.resolve(id + SUFFIX));
        } catch (IOException e) {
            LOG.warn("Failed deleting pre-key {}: {}", id, e.toString());
        }
    }

    public Map<Integer, KeyPair25519> inRange(int from, int toExclusive) {
        TreeMap<Integer, KeyPair25519> out = new TreeMap<>();
        for (int id = from; id < toExclusive; id++) {
            KeyPair25519 kp = cache.get(id);
            if (kp != null) out.put(id, kp);
        }
        return new LinkedHashMap<>(out);
    }

    public int size() { return cache.size(); }

    /** Snapshot of every pre-key currently loaded; used to seed downstream stores. */
    public Map<Integer, KeyPair25519> snapshot() {
        return new LinkedHashMap<>(cache);
    }

    /**
     * Drop every pre-key whose id is NOT among the {@code keepHighestN} highest ids
     * currently in the store. Pre-keys are consumed by peers via one-shot X3DH fetch,
     * so the very oldest ids are most likely already burned server-side — keeping
     * only the recent slice trades a small theoretical "peer fetched ancient id then
     * sat on it for months" risk for bounded disk usage.
     *
     * @return number of pre-keys actually deleted
     */
    public int pruneKeepHighest(int keepHighestN) {
        if (cache.size() <= keepHighestN) return 0;
        java.util.List<Integer> sortedIds = new java.util.ArrayList<>(cache.keySet());
        java.util.Collections.sort(sortedIds);
        int dropCount = sortedIds.size() - keepHighestN;
        int dropped = 0;
        for (int i = 0; i < dropCount; i++) {
            int id = sortedIds.get(i);
            cache.remove(id);
            try { Files.deleteIfExists(baseDir.resolve(id + SUFFIX)); dropped++; }
            catch (IOException e) { LOG.warn("Failed to delete stale pre-key {}: {}", id, e.toString()); }
        }
        LOG.info("Pruned {} stale pre-key(s); {} remaining (kept highest {} id(s))",
            dropped, cache.size(), keepHighestN);
        return dropped;
    }
}
