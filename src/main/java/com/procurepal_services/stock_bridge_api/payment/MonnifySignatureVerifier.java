package com.procurepal_services.stock_bridge_api.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Verifies the {@code monnify-signature} header: HMAC-SHA512 of the request body,
 * keyed with our Monnify secret key, hex-encoded.
 *
 * <h2>Why this takes a raw String and not a DTO</h2>
 * The hash is computed over the <b>exact bytes Monnify sent</b>. Letting Spring
 * deserialize the callback into a DTO and re-serializing it to hash would produce
 * a byte-for-byte different document - JSON object key order is not preserved
 * across a Jackson round trip, whitespace is dropped, numeric formatting is
 * normalised ({@code 100.00} becomes {@code 100.0}), and unmapped fields vanish
 * entirely. Every signature would fail, and the failure would look exactly like a
 * misconfigured secret key. The controller therefore binds {@code @RequestBody
 * String} and hands the untouched string here, and only afterwards parses it.
 *
 * <h2>Why MessageDigest.isEqual</h2>
 * {@code String.equals} returns as soon as two characters differ, so the time it
 * takes leaks how many leading characters an attacker guessed right. Feeding that
 * back into successive guesses recovers a valid signature one nibble at a time
 * without ever knowing the key. {@link MessageDigest#isEqual} compares in time
 * independent of where the first difference is, which closes that channel.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MonnifySignatureVerifier {

    private static final String HMAC_SHA512 = "HmacSHA512";

    private final MonnifyProperties properties;

    /**
     * @param rawBody the request body exactly as received - never re-serialized
     * @param providedSignature the {@code monnify-signature} header, possibly null
     * @return true only if a signature was supplied and matches
     */
    public boolean isValid(String rawBody, String providedSignature) {
        if (providedSignature == null || providedSignature.isBlank()) {
            return false;
        }
        if (rawBody == null) {
            return false;
        }
        if (properties.secretKey() == null || properties.secretKey().isBlank()) {
            // No key means nothing can be verified. Refusing is the only safe answer;
            // treating "cannot check" as "valid" would make an unconfigured
            // deployment accept anything.
            log.warn("A Monnify webhook arrived but no secret key is configured - cannot verify its signature");
            return false;
        }

        String expected = computeSignature(rawBody);
        if (expected == null) {
            return false;
        }

        // Compare the hex forms as bytes. Lower-cased first so a provider that
        // switches to upper-case hex does not read as a forgery; case is not a
        // secret, so normalising it leaks nothing.
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] providedBytes = providedSignature.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, providedBytes);
    }

    /** Exposed for the test that proves a correctly-signed body is accepted. */
    public String computeSignature(String rawBody) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA512);
            mac.init(new SecretKeySpec(properties.secretKey().getBytes(StandardCharsets.UTF_8), HMAC_SHA512));
            return HexFormat.of().formatHex(mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // Never let a crypto misconfiguration read as a valid signature.
            log.error("Could not compute the Monnify webhook signature: {}", e.getMessage());
            return null;
        }
    }

    /** See {@link MonnifyProperties#requireWebhookSignature()} - sandbox does not send the header at all. */
    public boolean isSignatureRequired() {
        return properties.requireWebhookSignature();
    }
}
