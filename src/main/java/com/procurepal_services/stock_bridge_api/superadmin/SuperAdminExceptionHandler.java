package com.procurepal_services.stock_bridge_api.superadmin;

import com.procurepal_services.stock_bridge_api.analytics.InvalidAnalyticsParameterException;
import com.procurepal_services.stock_bridge_api.auth.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Scoped to the superadmin controllers only - see ClientSignupExceptionHandler for why. */
@RestControllerAdvice(assignableTypes = {SuperAdminClientController.class, SuperAdminAnalyticsController.class})
public class SuperAdminExceptionHandler {

    @ExceptionHandler(ClientNotFoundException.class)
    public ResponseEntity<ApiError> handleClientNotFound(ClientNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage()));
    }

    /** Reuses AnalyticsController's exception type - the per-client analytics endpoints share its validation. */
    @ExceptionHandler(InvalidAnalyticsParameterException.class)
    public ResponseEntity<ApiError> handleInvalidAnalyticsParameter(InvalidAnalyticsParameterException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
    }
}
