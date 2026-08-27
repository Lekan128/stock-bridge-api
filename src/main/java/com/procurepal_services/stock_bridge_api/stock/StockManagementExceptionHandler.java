package com.procurepal_services.stock_bridge_api.stock;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.product.ProductNotFoundException;
import com.procurepal_services.stock_bridge_api.stock.dto.InsufficientStockErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Scoped to StockController only - see ClientSignupExceptionHandler for why. */
@RestControllerAdvice(assignableTypes = StockController.class)
public class StockManagementExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(ProductNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(StockMovementNotFoundException.class)
    public ResponseEntity<ApiError> handleMovementNotFound(StockMovementNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage()));
    }

    /**
     * 409, not 400 - a conflict between the request and the current state of the resource
     * (there simply is not enough stock), the same reasoning {@code CompanyVendorController}
     * gives for answering 409 rather than 400 on its own state conflict. Body carries the real
     * numbers alongside the message - MULTI_VENDOR_INVENTORY_DESIGN.md section 8.
     */
    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<InsufficientStockErrorResponse> handleInsufficientStock(InsufficientStockException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new InsufficientStockErrorResponse(
                        HttpStatus.CONFLICT.value(), ex.getMessage(), ex.getAvailableQuantity(), ex.getRequestedQuantity()));
    }

    @ExceptionHandler(CompanyVendorRequiredException.class)
    public ResponseEntity<ApiError> handleCompanyVendorRequired(CompanyVendorRequiredException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(InvalidStockAllocationException.class)
    public ResponseEntity<ApiError> handleInvalidAllocation(InvalidStockAllocationException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(InvalidStockUnitException.class)
    public ResponseEntity<ApiError> handleInvalidUnit(InvalidStockUnitException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
    }
}
