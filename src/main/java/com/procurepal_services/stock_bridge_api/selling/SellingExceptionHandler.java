package com.procurepal_services.stock_bridge_api.selling;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.ValidationErrors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {SellingSettingsController.class, SaleController.class, ProductPriceHistoryController.class})
public class SellingExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ValidationErrors.describe(ex)));
    }

    @ExceptionHandler(SellingNotAvailableException.class)
    public ResponseEntity<ApiError> handleNotAvailable(SellingNotAvailableException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(InvalidSettingSourceException.class)
    public ResponseEntity<ApiError> handleInvalidSource(InvalidSettingSourceException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(SaleNotFoundException.class)
    public ResponseEntity<ApiError> handleSaleNotFound(SaleNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(SaleFixRefusedException.class)
    public ResponseEntity<ApiError> handleFixRefused(SaleFixRefusedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(InvalidSalePriceException.class)
    public ResponseEntity<ApiError> handleInvalidPrice(InvalidSalePriceException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
    }

    /**
     * Who may cancel which sale is decided in SaleService, past the endpoint's gate - so the 403 is
     * raised here, with the service's sentence, rather than left to the filter chain's bare one.
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ApiError> handleDenied(org.springframework.security.access.AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(com.procurepal_services.stock_bridge_api.product.ProductNotFoundException.class)
    public ResponseEntity<ApiError> handleProductNotFound(com.procurepal_services.stock_bridge_api.product.ProductNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage()));
    }
}
