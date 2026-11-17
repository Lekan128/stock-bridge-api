package com.procurepal_services.stock_bridge_api.product;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Global on purpose: a stock write reaches a made-here item from stock, moves, transfers and deliveries alike. */
@RestControllerAdvice
public class MadeHereExceptionHandler {

    @ExceptionHandler(MadeHereException.class)
    public ResponseEntity<ApiError> handle(MadeHereException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
    }

    /** A "Made with" or a product's branches that can't be saved as sent (V92, V95). */
    @ExceptionHandler(MadeWithException.class)
    public ResponseEntity<ApiError> handle(MadeWithException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(ex.getMessage()));
    }

    /** A product used at a branch it isn't for (V92) - global for the same reason. */
    @ExceptionHandler(ProductNotForBranchException.class)
    public ResponseEntity<ApiError> handle(ProductNotForBranchException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
    }
}
