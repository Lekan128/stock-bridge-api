package com.procurepal_services.stock_bridge_api.client;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Scoped to ClientSignupController only (via assignableTypes) rather than
 * global: DataIntegrityViolationException is a generic Spring exception, and
 * a global handler for it would risk mislabeling an unrelated future
 * constraint violation elsewhere in the app as a "client identifier taken"
 * conflict.
 */
@RestControllerAdvice(assignableTypes = ClientSignupController.class)
public class ClientSignupExceptionHandler {

    @ExceptionHandler(ClientIdentifierTakenException.class)
    public ResponseEntity<ApiError> handleIdentifierTaken(ClientIdentifierTakenException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(SignupThrottledException.class)
    public ResponseEntity<ApiError> handleThrottled(SignupThrottledException ex) {
        long seconds = Math.max(1, (ex.getRetryAfter().toMillis() + 999) / 1000);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
                .body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(SignupRejectedException.class)
    public ResponseEntity<ApiError> handleRejected(SignupRejectedException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(PasswordMismatchException.class)
    public ResponseEntity<ApiError> handlePasswordMismatch(PasswordMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(ex.getMessage()));
    }

    /**
     * Safety net for the rare race where two signups pass the pre-check with
     * the same identifier at (almost) the same time - the DB's unique
     * constraint is the real source of truth here; this just keeps the
     * response a clean 409 instead of a raw 500.
     */
    /**
     * A field the form should have caught (an older app, or a direct call): the reason, in the
     * words the form would use, rather than Spring's bare "Bad Request" with nothing to show.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleInvalid(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getDefaultMessage())
                .distinct()
                .collect(java.util.stream.Collectors.joining(" "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(message.isBlank() ? "Check the details and try again." : message));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("Client identifier is already taken. Please choose a different one."));
    }
}
