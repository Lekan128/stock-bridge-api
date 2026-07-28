package com.procurepal_services.stock_bridge_api.notification;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Scoped to NotificationController only - see ClientSignupExceptionHandler for why. */
@RestControllerAdvice(assignableTypes = NotificationController.class)
public class NotificationExceptionHandler {

    @ExceptionHandler(NotificationNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotificationNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage()));
    }
}
