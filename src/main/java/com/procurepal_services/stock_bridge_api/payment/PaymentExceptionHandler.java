package com.procurepal_services.stock_bridge_api.payment;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.ValidationErrors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Scoped to PaymentController only - see ClientSignupExceptionHandler for why.
 *
 * <p>Note what is NOT handled here: the webhook endpoint returns its status
 * directly rather than by throwing, because a callback must never produce an
 * ApiError body. Monnify is not a user, has no interest in our error envelope, and
 * an exception escaping that handler would lose the
 * {@code payment_webhook_events} row that is the whole point of receiving it.
 */
@RestControllerAdvice(assignableTypes = PaymentController.class)
public class PaymentExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ValidationErrors.describe(ex)));
    }

    /** Also the answer for a payment belonging to another company - deliberately indistinguishable. */
    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(PaymentNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage()));
    }

    /** 409: the order is real and theirs, but its state forbids a new checkout. */
    @ExceptionHandler(OrderNotPayableException.class)
    public ResponseEntity<ApiError> handleNotPayable(OrderNotPayableException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
    }

    /**
     * 503 rather than 500: the deployment is missing credentials, the request was
     * fine, and the message steers the buyer to pay-on-delivery, which still works.
     */
    @ExceptionHandler(MonnifyNotConfiguredException.class)
    public ResponseEntity<ApiError> handleNotConfigured(MonnifyNotConfiguredException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ApiError(ex.getMessage()));
    }

    /**
     * 502: we asked Monnify and could not get an answer. The message is deliberately
     * vague to the buyer - the detail is in the log, keyed by paymentReference - and
     * says nothing about whether money moved, because at this point we genuinely do
     * not know and the reconciliation sweep is what will find out.
     */
    @ExceptionHandler(MonnifyApiException.class)
    public ResponseEntity<ApiError> handleProviderFailure(MonnifyApiException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiError("We could not reach the payment provider. Please try again in a moment."));
    }
}
