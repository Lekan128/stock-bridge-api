package com.procurepal_services.stock_bridge_api.email.preferences;

/**
 * The token in a {@code List-Unsubscribe} URL did not verify: absent, truncated,
 * hand-edited, forged, or minted under a signing key this deploy no longer holds.
 *
 * <h2>Why one exception covers all of those</h2>
 * Every cause collapses into this single type on purpose, and {@link
 * EmailPreferenceExceptionHandler} turns it into one 400 with one message. Telling
 * a caller <em>which</em> part of their token was wrong - "not base64", "signature
 * mismatch", "unknown key version" - is the same leak the constant-time comparison
 * in {@link UnsubscribeTokenService} exists to close, delivered through a much
 * wider channel. A public, unauthenticated endpoint owes an attacker no
 * diagnostics.
 *
 * <p>Unchecked, like every other exception in this codebase's service layer, so it
 * travels from {@link UnsubscribeService} to the advice without any intermediate
 * method having to declare it.
 */
public class InvalidUnsubscribeTokenException extends RuntimeException {

    public InvalidUnsubscribeTokenException() {
        // Deliberately vague and deliberately actionable in the only way that helps
        // a real person who somehow reached this: their link is stale or mangled,
        // and the fix is inside the app rather than another go at the link.
        super("This unsubscribe link is not valid. You can change your email preferences "
                + "from Settings after signing in.");
    }
}
