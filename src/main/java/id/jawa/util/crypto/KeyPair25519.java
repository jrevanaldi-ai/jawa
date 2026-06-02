// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.util.crypto;

import java.util.Arrays;
import java.util.Objects;

/**
 * Curve25519 key pair. Public and private keys are always 32 raw bytes (no leading
 * {@code 0x05} type byte — that prefix is a Signal Protocol wire convention applied
 * only by {@link Curve25519} when calling libsignal's curve operations).
 */
public record KeyPair25519(byte[] privateKey, byte[] publicKey) {
    public KeyPair25519 {
        Objects.requireNonNull(privateKey, "privateKey");
        Objects.requireNonNull(publicKey, "publicKey");
        if (privateKey.length != 32) {
            throw new IllegalArgumentException("privateKey must be 32 bytes, got " + privateKey.length);
        }
        if (publicKey.length != 32) {
            throw new IllegalArgumentException("publicKey must be 32 bytes, got " + publicKey.length);
        }
    }

    @Override public boolean equals(Object o) {
        return o instanceof KeyPair25519 k
            && Arrays.equals(privateKey, k.privateKey)
            && Arrays.equals(publicKey, k.publicKey);
    }
    @Override public int hashCode() {
        return Arrays.hashCode(privateKey) * 31 + Arrays.hashCode(publicKey);
    }
}
