package com.procurepal_services.stock_bridge_api.selling;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.ValidationErrors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = SellingSettingsController.class)
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
}
