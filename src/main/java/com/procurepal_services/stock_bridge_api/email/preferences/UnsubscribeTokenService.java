package com.procurepal_services.stock_bridge_api.email.preferences;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Mints and checks the token in a {@code List-Unsubscribe} link. The token
 * <em>is</em> the recipient's address plus an HMAC-SHA256 over it; there is no
 * database row anywhere behind it.
 *
 * <h2>Why this is stateless and signed, when email verification is not</h2>
 * Module B's verification token is a stored row with an expiry, and that is right
 * for verification and wrong here. The two links have opposite requirements and it
 * is worth being explicit, because "we already have a token table, use it" is the
 * obvious review comment.
 *
 * <p>A verification link <strong>must</strong> expire. It is a bearer credential
 * that flips an account into a trusted state, it is delivered to an address nobody
 * has yet proved anyone can read, and a stale one sitting in a forwarded thread is
 * a way into an account. Short-lived and revocable is the only safe shape, and
 * that needs storage.
 *
 * <p>An unsubscribe link <strong>must not</strong> expire, and this is the whole
 * argument. Mail clients surface {@code List-Unsubscribe} from the message
 * <em>currently on screen</em>, which may be eleven months old - people
 * unsubscribe when a campaign finally annoys them, not when it arrives. A link
 * that has expired does not degrade into a mild inconvenience: the reader has
 * pressed the unsubscribe button, nothing has happened, and the next button along
 * is "report spam". Gmail and Yahoo weight complaint rate above almost everything
 * else, so an expiring unsubscribe token converts directly into the deliverability
 * damage that the whole email-eligibility feature exists to prevent. It would also
 * mean storing a row per (recipient x campaign) forever, or garbage-collecting
 * rows whose entire purpose is to still be there later.
 *
 * <p>So: no expiry, therefore nothing worth storing, therefore no table. A signed
 * token carries its own meaning and the key is the only state.
 *
 * <h2>What the signature is actually defending against</h2>
 * Without one, the link would have to carry a bare address - {@code
 * ?address=someone@example.com} - and then anybody could unsubscribe anybody by
 * editing a query string. That is not a hypothetical annoyance: a competitor could
 * walk a customer list and silence every marketing address on the platform, and
 * the victims would never know, because a message that is not sent leaves no trace
 * in anyone's inbox. Binding the address into an HMAC means possessing a valid
 * token for {@code alice@example.com} proves you received a message addressed to
 * her, which is the closest thing to authentication a mail client can offer.
 *
 * <p>The comparison is {@link MessageDigest#isEqual} for the reason spelled out at
 * length in {@link
 * com.procurepal_services.stock_bridge_api.payment.MonnifySignatureVerifier}:
 * {@code String.equals} and {@code Arrays.equals} return at the first differing
 * byte, so how long they take leaks how many leading bytes an attacker guessed
 * right, and that feedback recovers a valid signature one byte at a time without
 * ever knowing the key. The endpoint here is public and unrate-limited, so that
 * channel would be wide open.
 *
 * <h2>Deliberately deterministic - the same address always yields the same token</h2>
 * No nonce, no timestamp, no random salt. Two consequences, both wanted.
 *
 * <p>First, a resend of the same campaign carries the same link, so a mail client
 * that has already recorded an unsubscribe for it does not offer the button twice,
 * and a retry of the POST is trivially idempotent (see {@link UnsubscribeService}).
 *
 * <p>Second - the trap - a token is <em>permanently</em> valid for its address once
 * it has been seen. Anyone who ever received a promotional message can unsubscribe
 * that address again at any time, forever. That is knowingly accepted, because the
 * capability it grants is exactly one boolean, on a flag the user can flip back
 * from inside the application ({@link EmailPreferenceController}), and which by
 * design cannot touch transactional mail at all. Deciding otherwise would mean
 * rotating tokens, which means expiring them, which lands back in the failure
 * mode above.
 *
 * <h2>Format</h2>
 * {@code base64url(address) . base64url(hmac)}, unpadded, so the whole thing is
 * safe in a query string with no escaping and the address is legible to an
 * operator reading a log or a header. Legible is not a leak: the recipient of the
 * message already knows who it was addressed to, and the token is only ever
 * transmitted to that recipient.
 *
 * <p>The MAC is taken over a domain-separated string rather than the bare address,
 * so a token minted here can never be replayed as a signature for some other
 * feature that later keys an HMAC off the same secret. Cheap now, impossible to
 * retrofit once links are in the wild - changing the prefix invalidates every
 * token ever issued.
 *
 * <h2>Never throws</h2>
 * Consistent with the rest of the email package. An unconfigured secret, a
 * malformed token, a base64 payload that is not valid UTF-8: all of them are a
 * {@code null} return, which the caller reads as "no link" or "not a valid token".
 * A crypto misconfiguration must never be able to read as a <em>valid</em>
 * signature, which is why {@link #sign} returning null fails every comparison
 * rather than skipping it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UnsubscribeTokenService {

    private static final String HMAC_SHA256 = "HmacSHA256";

    /**
     * Domain separation. See the Format section - never change this string, it
     * would invalidate every unsubscribe link already sitting in an inbox. A new
     * scheme gets a new prefix and both are accepted during the overlap.
     */
    private static final String SIGNING_DOMAIN = "procurepal:unsubscribe:v1:";

    /** Must match {@link UnsubscribeController}'s mapping. */
    private static final String UNSUBSCRIBE_PATH = "/api/email/unsubscribe";

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final UnsubscribeProperties properties;

    /** True when {@link #unsubscribeUrlFor} can produce a link. */
    public boolean canIssueLinks() {
        return properties.canIssueLinks();
    }

    /**
     * The absolute URL that goes inside the {@code List-Unsubscribe} header, or
     * null when this deploy cannot issue links.
     *
     * <p>Null is not an error and not an exception: it is the signal {@link
     * com.procurepal_services.stock_bridge_api.email.EmailSender} uses to refuse to
     * send promotional mail at all. Marketing with no working unsubscribe is worse
     * than no marketing.
     */
    public String unsubscribeUrlFor(String address) {
        if (!properties.canIssueLinks()) {
            return null;
        }
        String token = tokenFor(address);
        if (token == null) {
            return null;
        }
        // The token is already base64url, so nothing in it needs escaping - but
        // encode anyway rather than assert it. This is the one place a future
        // change to the token format could silently produce a broken link, and the
        // breakage would only be visible in somebody else's mail client.
        return properties.normalizedApiBaseUrl()
                + UNSUBSCRIBE_PATH
                + "?token="
                + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    /**
     * The signed token for one address, or null when unconfigured or the address is
     * unusable.
     *
     * @param address normalised internally, so a caller holding {@code
     *     " Ops@Example.com "} and a caller holding {@code "ops@example.com"} get
     *     the same token. That has to be true or a token minted from a {@code To:}
     *     header would not verify against the same address read back out of it.
     */
    public String tokenFor(String address) {
        String normalized = normalize(address);
        if (normalized == null || !properties.canVerifyTokens()) {
            return null;
        }
        byte[] mac = sign(normalized);
        if (mac == null) {
            return null;
        }
        return ENCODER.encodeToString(normalized.getBytes(StandardCharsets.UTF_8)) + "." + ENCODER.encodeToString(mac);
    }

    /**
     * The address a token vouches for, or null if it vouches for nothing.
     *
     * <p>Null covers every failure identically - absent, truncated, not base64, a
     * good payload with a forged MAC, a token minted under a rotated secret - and
     * the caller turns all of them into one indistinguishable 400. Distinguishing
     * them would tell an attacker which half of the token they got right, which is
     * the same information the constant-time compare exists to withhold.
     *
     * <p>Note what this method does <em>not</em> do: it never touches the database
     * and never asks whether the address belongs to anybody. A token for an address
     * with no user row verifies perfectly well and yields that address. That is
     * deliberate and is what keeps the endpoint from becoming an existence oracle -
     * see {@link UnsubscribeService}.
     */
    public String addressFrom(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        if (!properties.canVerifyTokens()) {
            // Cannot check, therefore cannot accept. Treating "no key" as "valid"
            // would let anyone unsubscribe anyone on an unconfigured deploy - the
            // same fail-closed reasoning as MonnifySignatureVerifier with no secret
            // key. Costs nothing in practice: with no key, no link was ever issued,
            // so there is no genuine token in the world to reject.
            log.warn("An unsubscribe token was presented but no app.email.unsubscribe.secret is configured "
                    + "- it cannot be verified and is being refused.");
            return null;
        }

        int separator = token.lastIndexOf('.');
        if (separator <= 0 || separator == token.length() - 1) {
            return null;
        }

        try {
            String address = new String(DECODER.decode(token.substring(0, separator)), StandardCharsets.UTF_8);
            byte[] presented = DECODER.decode(token.substring(separator + 1));
            // Re-normalise before signing: the payload is attacker-controlled, and
            // an address that round-trips to a different string than the one that
            // was signed must not verify. Also means the returned address is
            // exactly the form UnsubscribeService will compare against in SQL.
            String normalized = normalize(address);
            if (normalized == null) {
                return null;
            }
            byte[] expected = sign(normalized);
            if (expected == null || !MessageDigest.isEqual(expected, presented)) {
                return null;
            }
            return normalized;
        } catch (IllegalArgumentException e) {
            // Not base64. Ordinary for a truncated or hand-edited link, and for
            // every scanner that probes the endpoint - debug, not warn, or a bored
            // crawler fills the log.
            log.debug("Rejected an unsubscribe token that was not decodable.");
            return null;
        }
    }

    /** Null on any crypto failure, which every caller treats as "does not verify". */
    private byte[] sign(String normalizedAddress) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return mac.doFinal((SIGNING_DOMAIN + normalizedAddress).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Could not compute an unsubscribe token signature: {}", e.getMessage());
            return null;
        }
    }

    /**
     * The same normalisation {@code EmailMessage} and {@code EmailEligibility}
     * apply. It has to be the same one: a token is minted from an address in a
     * {@code To:} header and later compared against {@code lower(email)} in SQL, so
     * any divergence would mean signing one string and unsubscribing another.
     */
    private static String normalize(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        return address.trim().toLowerCase(Locale.ROOT);
    }
}
