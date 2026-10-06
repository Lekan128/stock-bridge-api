package com.procurepal_services.stock_bridge_api.email.webhook;

import com.procurepal_services.stock_bridge_api.email.ResendProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Verifies a Resend webhook delivery. Resend signs through Svix, so this is the Svix
 * scheme, implemented by hand from https://docs.svix.com/receiving/verifying-payloads/how-manual
 * rather than by pulling in the Svix SDK for one HMAC:
 *
 * <ol>
 *   <li>The signed content is {@code svix-id + "." + svix-timestamp + "." + body},
 *       over the body's exact bytes.</li>
 *   <li>The key is the endpoint's signing secret with its {@code whsec_} prefix
 *       removed, base64-decoded.</li>
 *   <li>The signature is base64(HMAC-SHA256(key, content)).</li>
 *   <li>{@code svix-signature} is a space-separated list of {@code v1,<sig>} entries
 *       (more than one during a secret rotation); any one matching is a pass.</li>
 * </ol>
 *
 * <h2>The timestamp is part of the check, not a nicety</h2>
 * Without it, one captured bounce notification could be replayed forever. The
 * timestamp is inside the signed content, so it cannot be refreshed by an attacker,
 * and a delivery outside {@link ResendProperties#webhookTolerance()} of now is
 * refused however valid its signature.
 *
 * <h2>No secret means no pass</h2>
 * Unlike the SNS path there is no "require signature" switch to turn off: a blank
 * secret fails every delivery. The notification body is the only evidence a bounce
 * happened, and acting on it unsigned would let anyone who finds the URL unverify any
 * customer on the platform.
 */
@Component
public class ResendSignatureVerifier {

    private static final String SECRET_PREFIX = "whsec_";

    private final ResendProperties properties;
    private final Clock clock;

    @Autowired
    public ResendSignatureVerifier(ResendProperties properties) {
        this(properties, Clock.systemUTC());
    }

    ResendSignatureVerifier(ResendProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public boolean isValid(String svixId, String svixTimestamp, String svixSignature, byte[] body) {
        if (!properties.hasWebhookSecret() || isBlank(svixId) || isBlank(svixTimestamp)
                || isBlank(svixSignature) || body == null) {
            return false;
        }
        if (!isFresh(svixTimestamp)) {
            return false;
        }
        byte[] expected;
        try {
            expected = sign(svixId.trim(), svixTimestamp.trim(), body);
        } catch (Exception e) {
            // A secret that is not valid base64 - misconfiguration, not an attack -
            // and it must fail closed like every other path here.
            return false;
        }
        for (String entry : svixSignature.trim().split(" +")) {
            int comma = entry.indexOf(',');
            if (comma < 0 || !"v1".equals(entry.substring(0, comma))) {
                continue;
            }
            byte[] presented;
            try {
                presented = Base64.getDecoder().decode(entry.substring(comma + 1));
            } catch (IllegalArgumentException e) {
                continue;
            }
            // Constant-time, so the comparison leaks nothing about how close a
            // forged signature came.
            if (MessageDigest.isEqual(expected, presented)) {
                return true;
            }
        }
        return false;
    }

    private boolean isFresh(String svixTimestamp) {
        try {
            Instant sent = Instant.ofEpochSecond(Long.parseLong(svixTimestamp.trim()));
            Duration skew = Duration.between(sent, clock.instant()).abs();
            return skew.compareTo(properties.webhookTolerance()) <= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private byte[] sign(String svixId, String svixTimestamp, byte[] body) throws Exception {
        String secret = properties.webhookSecret().trim();
        if (secret.startsWith(SECRET_PREFIX)) {
            secret = secret.substring(SECRET_PREFIX.length());
        }
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(secret), "HmacSHA256"));
        mac.update((svixId + "." + svixTimestamp + ".").getBytes(StandardCharsets.UTF_8));
        return mac.doFinal(body);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
