package com.procurepal_services.stock_bridge_api.vendor.waitlist;

import java.time.Duration;
import lombok.Getter;

/**
 * Thrown when a caller has submitted the public vendor form more often than
 * {@code app.vendor-waitlist.submit-limit} allows. Becomes a 429 with a
 * {@code Retry-After} header - see {@link VendorWaitlistExceptionHandler}.
 *
 * <h2>Why the message is vague where VerificationResendThrottledException's is not</h2>
 * That one is allowed to be specific because its caller is authenticated and is
 * being told a fact about their own account, so there is nothing to enumerate.
 * This caller is anonymous, and there are two budgets behind this refusal - the
 * source address and the submitted email. Saying which one ran out would answer
 * "has this address applied to ProcurePaddy in the last hour" for any address a
 * stranger cares to type, which is precisely the disclosure
 * {@code VendorWaitlistApplicationResponse} exists to prevent on the success
 * path. One message covers both.
 *
 * <p>{@code retryAfter} is still honest, and does not undo that: it is the wait,
 * which both budgets share a window with, and the frontend needs it to disable
 * the button for the right duration rather than let somebody click into a wall.
 */
@Getter
public class VendorWaitlistThrottledException extends RuntimeException {

    private final Duration retryAfter;

    public VendorWaitlistThrottledException(Duration retryAfter) {
        super("Too many applications submitted recently. Please wait a little and try again.");
        this.retryAfter = retryAfter == null || retryAfter.isNegative() ? Duration.ZERO : retryAfter;
    }
}
