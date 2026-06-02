// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.noise;

import com.google.protobuf.InvalidProtocolBufferException;
import id.jawa.core.WaConstants;
import id.jawa.proto.Wa;
import id.jawa.util.crypto.Curve25519;

/**
 * Verifies the {@link id.jawa.proto.Wa.CertChain} payload that arrives in
 * {@code HandshakeMessage.serverHello.payload}.
 *
 * <p>Two-level chain:
 * <pre>
 *   WA_CERT_PUBLIC_KEY (hardcoded long-term anchor)
 *       └─ verifies → intermediate.details + intermediate.signature
 *                       └─ contains intermediate.key (signs the leaf)
 *                           └─ verifies → leaf.details + leaf.signature
 * </pre>
 *
 * <p>Also asserts {@code intermediate.details.issuerSerial == WA_CERT_SERIAL (0)}.
 *
 * <p>Mirrors {@code processHandshake} in Baileys' {@code noise-handler.ts}.
 */
public final class CertChainValidator {

    private CertChainValidator() {}

    /** Throws {@link CertChainException} on any failure. */
    public static void validate(byte[] expectedServerStatic, byte[] certChainBytes) {
        final Wa.CertChain chain;
        try {
            chain = Wa.CertChain.parseFrom(certChainBytes);
        } catch (InvalidProtocolBufferException e) {
            throw new CertChainException("CertChain proto malformed", e);
        }

        Wa.CertChain.NoiseCertificate leaf = chain.getLeaf();
        Wa.CertChain.NoiseCertificate intermediate = chain.getIntermediate();

        if (!leaf.hasDetails() || !leaf.hasSignature()) {
            throw new CertChainException("invalid noise leaf certificate");
        }
        if (!intermediate.hasDetails() || !intermediate.hasSignature()) {
            throw new CertChainException("invalid noise intermediate certificate");
        }

        final Wa.CertChain.NoiseCertificate.Details intermediateDetails;
        try {
            intermediateDetails = Wa.CertChain.NoiseCertificate.Details.parseFrom(intermediate.getDetails());
        } catch (InvalidProtocolBufferException e) {
            throw new CertChainException("intermediate Details proto malformed", e);
        }

        // Intermediate's pubkey verifies the leaf
        byte[] intermediateKey = intermediateDetails.getKey().toByteArray();
        if (!Curve25519.verify(intermediateKey, leaf.getDetails().toByteArray(), leaf.getSignature().toByteArray())) {
            throw new CertChainException("noise certificate signature invalid");
        }

        // WA's long-term anchor verifies the intermediate
        if (!Curve25519.verify(WaConstants.WA_CERT_PUBLIC_KEY,
                intermediate.getDetails().toByteArray(),
                intermediate.getSignature().toByteArray())) {
            throw new CertChainException("noise intermediate certificate signature invalid");
        }

        // Anchor identity must match
        if (intermediateDetails.getIssuerSerial() != WaConstants.WA_CERT_SERIAL) {
            throw new CertChainException("certification match failed (issuerSerial="
                + intermediateDetails.getIssuerSerial() + ")");
        }

        // Note: Baileys does not bind leaf.details.key to expectedServerStatic.
        // Matching reference behaviour. {@code expectedServerStatic} kept on the API for future hardening.
    }

    /** Thrown if the cert chain is malformed or fails verification. */
    public static final class CertChainException extends RuntimeException {
        public CertChainException(String message) { super(message); }
        public CertChainException(String message, Throwable cause) { super(message, cause); }
    }
}
