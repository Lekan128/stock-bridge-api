package com.procurepal_services.stock_bridge_api.demo;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Global on purpose: the demo guards sit in several features, each with its own scoped advice. */
@RestControllerAdvice
public class DemoExceptionHandler {

    @ExceptionHandler(DemoActionNotAllowedException.class)
    public ResponseEntity<ApiError> handle(DemoActionNotAllowedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiError(ex.getMessage()));
    }
}
