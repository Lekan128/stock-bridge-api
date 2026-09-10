package com.procurepal_services.stock_bridge_api.email.webhook;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Scoped to {@link SesNotificationController} only - see {@code
 * ClientSignupExceptionHandler} for why advices in this codebase are never global.
 *
 * <h2>This is a backstop, not the error path</h2>
 * Worth saying plainly, because the class looks like it does more than it does.
 * {@link SesNotificationService} resolves every outcome it knows about - a bad
 * signature, a refused URL, a malformed body, a failure to apply - into a
 * {@link SesNotificationOutcome} and returns a status directly. Nothing on the
 * normal path throws. That is deliberate, and it is the same reasoning
 * {@code PaymentExceptionHandler} records for the Monnify webhook: an exception
 * escaping the handler would lose the {@code ses_notification_events} row that is
 * the entire point of receiving the message, and AWS is not a user with any interest
 * in our error envelope.
 *
 * <p>What is left for this class is the two things that can go wrong <em>outside</em>
 * the service: a URL refused while the guard is called from somewhere the service
 * does not already wrap, and anything Spring itself throws before the handler is
 * reached. Both answer a bare status with a minimal body.
 */
@RestControllerAdvice(assignableTypes = SesNotificationController.class)
public class SesWebhookExceptionHandler {

    /**
     * 400. Not 500 - the request was well-formed and was refused on policy - and not
     * 2xx, because a body containing a URL we will not fetch has not been processed
     * and should not be reported as though it had. The message is the guard's, which
     * names the field and the host; it reaches AWS, which ignores it, and the log,
     * which is where it is read.
     */
    @ExceptionHandler(SnsEndpointRefusedException.class)
    public ResponseEntity<ApiError> handleRefusedEndpoint(SnsEndpointRefusedException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(ex.getMessage()));
    }
}
