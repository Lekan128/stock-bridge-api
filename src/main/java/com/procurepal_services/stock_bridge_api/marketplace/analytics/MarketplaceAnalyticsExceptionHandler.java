package com.procurepal_services.stock_bridge_api.marketplace.analytics;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.ValidationErrors;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Scoped to this module's controller only - see ClientSignupExceptionHandler for why these
 * are per-feature, and MarketplaceAccessExceptionHandler for the one deliberate exception
 * (the platform-owner 403, handled globally and therefore absent from here).
 *
 * Everything below is a 400. That is the point: analytics take their entire input from the
 * query string, which arrives from a bookmark, a shared link or somebody editing the URL,
 * and the unhandled path turns a bad parameter into a 500 - or, once Spring's /error
 * re-dispatch has lost the request's authentication, into a 403 that reads as "you may not
 * see these numbers".
 */
@RestControllerAdvice(assignableTypes = MarketplaceAnalyticsController.class)
public class MarketplaceAnalyticsExceptionHandler {

    @ExceptionHandler(InvalidAnalyticsRangeException.class)
    public ResponseEntity<ApiError> handleInvalidRange(InvalidAnalyticsRangeException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
    }

    /**
     * A query parameter that will not bind: {@code ?granularity=HOURLY}, {@code ?limit=all},
     * {@code ?from=yesterday}. The message names the parameter but never echoes the value,
     * so a crafted URL cannot reflect content back through the error body.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiError("'" + ex.getName() + "' is not a valid value for this request."));
    }

    /** {@code @Min}/{@code @Max} on the request params, which @Validated raises as a violation rather than a bind error. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("One or more query parameters are out of range."));
    }

    /** Present for symmetry with the rest of the marketplace package; no route here takes a body today. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ValidationErrors.describe(ex)));
    }
}
