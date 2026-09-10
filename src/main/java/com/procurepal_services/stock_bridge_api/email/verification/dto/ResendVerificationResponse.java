package com.procurepal_services.stock_bridge_api.email.verification.dto;

/**
 * The answer to {@code POST /api/me/email/verification}.
 *
 * <h2>Why "nothing was sent" is a 200 and not an error</h2>
 * Two ordinary situations produce no email: the address is already verified, and
 * the account has no usable address to send to. Neither is a failure of the
 * request - the caller asked for their address to be confirmed and, in the first
 * case, it already is. Returning 4xx would make the frontend render an error for
 * an account that is in the state the user wanted, and would push both cases into
 * an error path that has to re-derive what actually happened.
 *
 * <p>So the caller gets {@code sent=false} and a sentence explaining which it was.
 * That is safe to be specific about, unlike the verify endpoint: this is an
 * authenticated request about the caller's own account, so there is nothing here
 * they could not already read from {@code /api/me}.
 */
public record ResendVerificationResponse(boolean sent, String message) {

    public static ResendVerificationResponse sent(String address) {
        return new ResendVerificationResponse(
                true, "We have sent a confirmation link to " + address + ". It expires shortly, so use it soon.");
    }

    public static ResendVerificationResponse alreadyVerified() {
        return new ResendVerificationResponse(false, "Your email address is already confirmed.");
    }

    public static ResendVerificationResponse noAddress() {
        return new ResendVerificationResponse(
                false, "There is no email address on your profile to confirm. Add one and try again.");
    }
}
