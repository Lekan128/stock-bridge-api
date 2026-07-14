package com.procurepal_services.stock_bridge_api.analytics;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Scoped to AnalyticsController only - see ClientSignupExceptionHandler for why. */
@RestControllerAdvice(assignableTypes = AnalyticsController.class)
public class AnalyticsExceptionHandler {

    @ExceptionHandler(InvalidAnalyticsParameterException.class)
    public ResponseEntity<ApiError> handleInvalidParameter(InvalidAnalyticsParameterException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
    }
}
