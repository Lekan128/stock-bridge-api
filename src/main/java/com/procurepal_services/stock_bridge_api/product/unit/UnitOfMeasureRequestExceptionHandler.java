package com.procurepal_services.stock_bridge_api.product.unit;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.ValidationErrors;
import com.procurepal_services.stock_bridge_api.user.UserNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Scoped to {@link UnitOfMeasureRequestController} alone, never global - same
 * rule and same reason as {@code ProfileExceptionHandler}: the default
 * validation-failure path re-dispatches through {@code /error}, where {@code
 * JwtAuthenticationFilter} does not re-run, turning a 400 into a confusing
 * 403 for an otherwise-authenticated caller.
 */
@RestControllerAdvice(assignableTypes = UnitOfMeasureRequestController.class)
public class UnitOfMeasureRequestExceptionHandler {

    /**
     * Only reachable with a token whose user row has since been deleted - the
     * id comes from the principal, never from the request body.
     */
    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(UserNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(ValidationErrors.describe(ex)));
    }
}
