package com.procurepal_services.stock_bridge_api.email.webhook;

import java.security.PublicKey;

/**
 * Turns a {@code SigningCertURL} into the public key that verifies an SNS message.
 *
 * <p>An interface with one production implementation, which needs justifying since
 * this codebase does not add interfaces reflexively. The reason is that
 * {@link SnsSignatureVerifier} is the security control in this module and has to be
 * testable without a network: the canonical-string construction, the version 1 / 2
 * algorithm split, the tampered-field cases and the wrong-key case are all pure
 * cryptography, and they are exactly the logic most likely to be got wrong. Behind
 * this seam a test signs a fixture with a key pair it generated in-process and the
 * verifier does real RSA against it, which is a far stronger test than mocking the
 * verifier itself would be. The alternative - a static HTTP fetch inside the
 * verifier - would make every one of those cases require either a stub server or a
 * live AWS certificate.
 *
 * @see HttpSnsCertificateLoader the real one
 */
public interface SnsCertificateLoader {

    /**
     * @param signingCertUrl the raw, untrusted {@code SigningCertURL} from the
     *     message body. Implementations must validate it through
     *     {@link SnsEndpointGuard} before fetching anything.
     * @return the certificate's public key
     * @throws SnsEndpointRefusedException if the URL is not an AWS SNS signing
     *     certificate URL
     * @throws IllegalStateException if the certificate could not be fetched or parsed
     */
    PublicKey publicKeyFor(String signingCertUrl);
}
