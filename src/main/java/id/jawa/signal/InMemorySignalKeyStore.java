// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.signal;

import id.jawa.util.crypto.KeyPair25519;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe {@link SignalKeyStore} with optional file-backed persistence.
 *
 * <p>When constructed with a {@link FilePreKeyStorage}, every {@link #putPreKey} /
 * {@link #removePreKey} writes through to disk and {@link #getPreKey} reads from the
 * cache populated at load. When the storage is {@code null}, behavior is pure
 * in-memory and pre-keys are lost on restart.
 */
public final class InMemorySignalKeyStore implements SignalKeyStore {

    private final Map<Integer, KeyPair25519> preKeys = new ConcurrentHashMap<>();
    private final FilePreKeyStorage storage; // null = in-memory only

    public InMemorySignalKeyStore() {
        this(null);
    }

    public InMemorySignalKeyStore(FilePreKeyStorage storage) {
        this.storage = storage;
        if (storage != null) preKeys.putAll(storage.snapshot());
    }

    @Override
    public KeyPair25519 getPreKey(int id) { return preKeys.get(id); }

    @Override
    public void putPreKey(int id, KeyPair25519 kp) {
        preKeys.put(id, kp);
        if (storage != null) storage.put(id, kp);
    }

    @Override
    public void removePreKey(int id) {
        preKeys.remove(id);
        if (storage != null) storage.remove(id);
    }

    @Override
    public Map<Integer, KeyPair25519> getPreKeysInRange(int from, int toExclusive) {
        Map<Integer, KeyPair25519> out = new TreeMap<>();
        for (int id = from; id < toExclusive; id++) {
            KeyPair25519 kp = preKeys.get(id);
            if (kp != null) out.put(id, kp);
        }
        return new LinkedHashMap<>(out); // stable insertion order from TreeMap iteration
    }

    public int size() { return preKeys.size(); }
}
