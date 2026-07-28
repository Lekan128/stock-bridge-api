package com.procurepal_services.stock_bridge_api.address;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.ValidationErrors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Scoped to DeliveryAddressController only - see ClientSignupExceptionHandler for why. */
@RestControllerAdvice(assignableTypes = DeliveryAddressController.class)
public class DeliveryAddressExceptionHandler {

    @ExceptionHandler(DeliveryAddressNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(DeliveryAddressNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(InvalidDeliveryAddressException.class)
    public ResponseEntity<ApiError> handleInvalid(InvalidDeliveryAddressException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ValidationErrors.describe(ex)));
    }
}
