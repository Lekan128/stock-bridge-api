package com.procurepal_services.stock_bridge_api.selling;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** A branch price refused (V107), wherever it comes from - the branch page, the product form: 409 with why. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BranchPriceExceptionHandler {

    @ExceptionHandler(BranchPriceException.class)
    public ResponseEntity<ApiError> handle(BranchPriceException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
    }
}
