package com.procurepal_services.stock_bridge_api.email.verification;

import java.time.Duration;
import lombok.Getter;

/**
 * Thrown when an authenticated user has asked for a verification link more often
 * than {@code app.email.verification.resend-limit} allows. Becomes a 429 with a
 * {@code Retry-After} header - see {@link EmailVerificationExceptionHandler}.
 *
 * <h2>Why this one is allowed to be specific, when the token failure is not</h2>
 * {@link InvalidVerificationTokenException} hides everything because its caller is
 * anonymous and may be probing. This caller is authenticated and is being told
 * something about their own account, so there is nothing to enumerate: they
 * already know the account exists, because they are signed into it. The only fact
 * disclosed is how many times they themselves have pressed a button.
 *
 * <p>Being specific here has real value. A bare refusal invites the user to keep
 * clicking, which is exactly the traffic the limit exists to stop; naming the wait
 * lets the frontend disable the control for that long and turns a rate limit into
 * a piece of UI.
 */
@Getter
public class VerificationResendThrottledException extends RuntimeException {

    private final Duration retryAfter;

    public VerificationResendThrottledException(Duration retryAfter) {
        super("Too many verification emails requested. Please wait a few minutes and try again.");
        this.retryAfter = retryAfter == null || retryAfter.isNegative() ? Duration.ZERO : retryAfter;
    }
}
