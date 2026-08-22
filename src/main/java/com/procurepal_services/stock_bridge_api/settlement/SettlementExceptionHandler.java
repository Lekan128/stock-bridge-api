package com.procurepal_services.stock_bridge_api.settlement;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.marketplace.analytics.InvalidAnalyticsRangeException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Scoped to the VENDOR-facing statement controller only. The super admin settlement
 * controller is served by {@code SuperAdminExceptionHandler} instead, which is that
 * surface's single advice and whose {@code assignableTypes} allow-list has been
 * extended to name it - see the warning in that class about a controller missing
 * from the list getting none of its handlers.
 *
 * <p>Everything here is a 400, for the reason
 * {@code VendorSalesAnalyticsExceptionHandler} gives: a statement takes its entire
 * input from the query string, which arrives from a bookmark or somebody editing a
 * URL, and the unhandled path turns a bad parameter into a 500 - or, once Spring's
 * /error re-dispatch has lost the request's authentication, into a 403 that reads
 * as "you may not see your own money".
 *
 * <p>The seller 403 is deliberately absent: {@code VendorAccessExceptionHandler}
 * handles {@code VendorNotAllowedException} globally for every controller in the
 * application, so a feature package does not need its own advice for it.
 */
@RestControllerAdvice(assignableTypes = VendorStatementController.class)
public class SettlementExceptionHandler {

    /**
     * Reuses the analytics module's range exception rather than declaring a twin,
     * for the reason {@code SuperAdminExceptionHandler} gives when it does the same:
     * the range a screen will refuse should not depend on which screen it is, and a
     * second type would be a second message and, eventually, a second bound.
     */
    @ExceptionHandler(InvalidAnalyticsRangeException.class)
    public ResponseEntity<ApiError> handleInvalidRange(InvalidAnalyticsRangeException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
    }

    /** {@code ?from=yesterday}. Names the parameter, never echoes the value. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiError("'" + ex.getName() + "' is not a valid value for this request."));
    }

    /**
     * Reachable on this surface only through
     * {@code VendorStatementService.statementForSeller}, which the vendor routes do
     * not call - but mapped anyway so the type has a defined status everywhere it
     * can appear rather than a 500 on the one path somebody adds later.
     */
    @ExceptionHandler(SettlementNotAllowedException.class)
    public ResponseEntity<ApiError> handleNotAllowed(SettlementNotAllowedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
    }
}
