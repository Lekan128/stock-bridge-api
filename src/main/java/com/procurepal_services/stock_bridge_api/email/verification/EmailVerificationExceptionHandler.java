package com.procurepal_services.stock_bridge_api.email.verification;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.ValidationErrors;
import com.procurepal_services.stock_bridge_api.user.UserNotFoundException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Scoped to this module's two controllers, never global - same rule and same reason
 * as {@code ClientSignupExceptionHandler} and {@code EmailPreferenceExceptionHandler}.
 * {@link UserNotFoundException} in particular is a shared type that other advices
 * already map differently, and a global handler for it would quietly take over
 * their behaviour.
 */
@RestControllerAdvice(
        assignableTypes = {EmailVerificationController.class, EmailVerificationResendController.class})
public class EmailVerificationExceptionHandler {

    /**
     * 400 for every way a token can be bad, with the one message the exception
     * carries. Deliberately not 404 for "no such token" and 410 for "expired": the
     * status code is as much of an oracle as the body, and a client that could tell
     * those apart could tell whether a guessed token had ever existed. See
     * {@link InvalidVerificationTokenException}.
     *
     * <p>Also deliberately not 401. The caller has no session to have expired, and
     * a 401 from an endpoint the frontend calls while a user may be signed in
     * elsewhere is the response its interceptor treats as "log this person out".
     * Same reasoning {@code ProfileExceptionHandler} gives for the wrong-password
     * case.
     */
    @ExceptionHandler(InvalidVerificationTokenException.class)
    public ResponseEntity<ApiError> handleInvalidToken(InvalidVerificationTokenException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(ex.getMessage()));
    }

    /**
     * 429 with {@code Retry-After}, in seconds, as RFC 9110 specifies.
     *
     * <p>The header is not decoration: it is the machine-readable half of the
     * message, and it is what lets the frontend disable the button for the right
     * duration instead of guessing. Rounded UP, so a client that waits exactly as
     * long as it is told is never refused a second time for being a few hundred
     * milliseconds early - and a floor of one second, because {@code Retry-After: 0}
     * reads as "retry immediately", which is precisely what a rate limiter must not
     * say.
     *
     * <p><strong>Trap worth knowing before anyone "helpfully" caps this value.</strong>
     * The number here is honest, so with the default one-hour window it is close to
     * 3600. Browsers and {@code fetch} ignore it, which is what the frontend uses.
     * Apache HttpClient 5 does not: its default retry strategy treats 429 as
     * retryable and SLEEPS for exactly this long before trying again, so a Java
     * caller on defaults appears to hang rather than to be rate limited. That is the
     * client's choice rather than a reason to lie in the header - a shortened value
     * would just make such a client retry into a refusal it was told to expect - but
     * it is why the integration test for this response uses the JDK HTTP client
     * instead of TestRestTemplate, and it is the first thing to check if an
     * integration ever "hangs on resend".
     */
    @ExceptionHandler(VerificationResendThrottledException.class)
    public ResponseEntity<ApiError> handleThrottled(VerificationResendThrottledException ex) {
        long seconds = Math.max(1, (ex.getRetryAfter().toMillis() + 999) / 1000);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
                .body(new ApiError(ex.getMessage()));
    }

    /**
     * Only reachable from the authenticated resend endpoint, and only with a token
     * whose user has since been deleted - the id comes from the principal, never
     * from the request. The public verify endpoint never raises this: a missing
     * user there is folded into the indistinguishable token refusal above, because
     * distinguishing it would confirm that an account once existed.
     */
    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(UserNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage()));
    }

    /**
     * Handled here rather than left to Spring's default resolver, for the reason
     * {@code ProfileExceptionHandler} spells out: the default path calls
     * {@code response.sendError}, which re-dispatches through {@code /error}, and on
     * an ERROR dispatch {@code JwtAuthenticationFilter} does not re-run - so a
     * validation failure arrives at {@code /error} unauthenticated and comes back as
     * a confusing 403 instead of a 400.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(ValidationErrors.describe(ex)));
    }
}
