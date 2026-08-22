package com.procurepal_services.stock_bridge_api.vendor.waitlist;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.ValidationErrors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Scoped to {@link VendorWaitlistController} alone, never global - same rule and
 * same reason as {@code ClientSignupExceptionHandler} and
 * {@code EmailVerificationExceptionHandler}.
 */
@RestControllerAdvice(assignableTypes = VendorWaitlistController.class)
public class VendorWaitlistExceptionHandler {

    /**
     * 429 with {@code Retry-After} in seconds, as RFC 9110 specifies - rounded UP
     * with a floor of one second, so a client that waits exactly as long as it was
     * told is never refused for being a few hundred milliseconds early and never
     * reads {@code Retry-After: 0} as "retry immediately".
     *
     * <p>Byte-for-byte the same arithmetic as
     * {@code EmailVerificationExceptionHandler.handleThrottled}, including the trap
     * documented there: the number is honest, so with the default one-hour window
     * it is close to 3600, and Apache HttpClient 5 on its default retry strategy
     * SLEEPS for exactly that long rather than surfacing the 429. Browsers and
     * {@code fetch} - which is what the React form uses - ignore it. That is the
     * client's choice rather than a reason to lie in the header, but it is the
     * first thing to check if an integration ever "hangs on apply".
     */
    @ExceptionHandler(VendorWaitlistThrottledException.class)
    public ResponseEntity<ApiError> handleThrottled(VendorWaitlistThrottledException ex) {
        long seconds = Math.max(1, (ex.getRetryAfter().toMillis() + 999) / 1000);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
                .body(new ApiError(ex.getMessage()));
    }

    /**
     * Handled here rather than left to Spring's default resolver, for the reason
     * {@code ProfileExceptionHandler} spells out: the default path calls
     * {@code response.sendError}, which re-dispatches through {@code /error}, and a
     * validation failure would come back as a confusing 403 instead of a 400.
     *
     * <p>{@code ValidationErrors.describe} names the offending field, which is what
     * lets the React form put the message under the right input rather than at the
     * top of the page - the same contract the signup form already relies on.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(ValidationErrors.describe(ex)));
    }
}
