package com.procurepal_services.stock_bridge_api.cart;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.ValidationErrors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Scoped to CartController only - see ClientSignupExceptionHandler for why. */
@RestControllerAdvice(assignableTypes = CartController.class)
public class CartExceptionHandler {

    @ExceptionHandler(CatalogProductUnavailableException.class)
    public ResponseEntity<ApiError> handleUnavailable(CatalogProductUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(CartItemNotFoundException.class)
    public ResponseEntity<ApiError> handleMissingLine(CartItemNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(InsufficientCatalogStockException.class)
    public ResponseEntity<ApiError> handleStock(InsufficientCatalogStockException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
    }

    /**
     * Handled here rather than left to Spring's default: the /error re-dispatch
     * loses the request's authentication and reports 403, which is a confusing
     * answer to a malformed quantity. See ProfileExceptionHandler.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ValidationErrors.describe(ex)));
    }
}
