package com.procurepal_services.stock_bridge_api.vendor.analytics;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.marketplace.analytics.InvalidAnalyticsRangeException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Scoped to this module's controller only - see ClientSignupExceptionHandler for
 * why these are per-feature, and VendorAccessExceptionHandler for the one
 * deliberate exception (the seller 403, handled globally and therefore absent
 * from here).
 *
 * <p>A near-copy of {@code MarketplaceAnalyticsExceptionHandler} and deliberately
 * not a shared advice: {@code @RestControllerAdvice(assignableTypes = ...)} is an
 * allow-list, so making one advice serve both modules would mean a class in the
 * marketplace package naming a controller in the vendor package, and every future
 * analytics surface editing a file it does not own. The handlers themselves are
 * four lines each; the coupling would cost more than the duplication.
 *
 * <p>Everything below is a 400. That is the point: analytics take their entire
 * input from the query string, which arrives from a bookmark, a shared link or
 * somebody editing the URL, and the unhandled path turns a bad parameter into a
 * 500 - or, once Spring's /error re-dispatch has lost the request's
 * authentication, into a 403 that reads as "you may not see your own numbers".
 */
@RestControllerAdvice(assignableTypes = VendorSalesAnalyticsController.class)
public class VendorSalesAnalyticsExceptionHandler {

    @ExceptionHandler(InvalidAnalyticsRangeException.class)
    public ResponseEntity<ApiError> handleInvalidRange(InvalidAnalyticsRangeException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
    }

    /**
     * A query parameter that will not bind: {@code ?granularity=HOURLY},
     * {@code ?limit=all}, {@code ?from=yesterday}. The message names the parameter
     * but never echoes the value, so a crafted URL cannot reflect content back
     * through the error body.
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
}
