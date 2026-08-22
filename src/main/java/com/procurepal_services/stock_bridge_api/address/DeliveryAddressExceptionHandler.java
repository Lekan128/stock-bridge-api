package com.procurepal_services.stock_bridge_api.address;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.ValidationErrors;
import com.procurepal_services.stock_bridge_api.vendor.pickup.VendorPickupAddressController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Scoped to the two controllers that serve this table - see ClientSignupExceptionHandler
 * for why per-feature rather than global.
 *
 * <p>{@code VendorPickupAddressController} is listed because it runs the same service and
 * therefore raises the same three exceptions; without it, a vendor naming a pickup address
 * that does not exist would get a 500 instead of a 404. The list is an allow-list and must
 * be kept so: a third caller of DeliveryAddressService needs adding here too.
 */
@RestControllerAdvice(
        assignableTypes = {DeliveryAddressController.class, VendorPickupAddressController.class})
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
