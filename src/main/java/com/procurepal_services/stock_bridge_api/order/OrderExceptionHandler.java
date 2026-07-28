package com.procurepal_services.stock_bridge_api.order;

import com.procurepal_services.stock_bridge_api.address.DeliveryAddressNotFoundException;
import com.procurepal_services.stock_bridge_api.address.InvalidDeliveryAddressException;
import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.ValidationErrors;
import com.procurepal_services.stock_bridge_api.cart.CatalogProductUnavailableException;
import com.procurepal_services.stock_bridge_api.stock.InsufficientStockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Scoped to the two buyer-facing order controllers - see ClientSignupExceptionHandler
 * for why advices stay narrow. It also has to catch the address and cart exceptions,
 * because checkout legitimately raises them (an inline address with a bad state, a
 * line that stopped being purchasable between quote and submit) and those feature
 * advices are scoped to their own controllers.
 */
@RestControllerAdvice(assignableTypes = {OrderController.class, CheckoutController.class})
public class OrderExceptionHandler {

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(OrderNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(CatalogProductUnavailableException.class)
    public ResponseEntity<ApiError> handleProductGone(CatalogProductUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(DeliveryAddressNotFoundException.class)
    public ResponseEntity<ApiError> handleAddressGone(DeliveryAddressNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(CheckoutNotAllowedException.class)
    public ResponseEntity<ApiError> handleNotAllowed(CheckoutNotAllowedException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(InvalidDeliveryAddressException.class)
    public ResponseEntity<ApiError> handleBadAddress(InvalidDeliveryAddressException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ApiError> handleInsufficientStock(InsufficientStockException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
    }

    /** 409, not 400: the request was valid, the order simply moved on underneath it. */
    @ExceptionHandler(InvalidOrderTransitionException.class)
    public ResponseEntity<ApiError> handleTransition(InvalidOrderTransitionException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(PaymentAmountMismatchException.class)
    public ResponseEntity<ApiError> handleAmountMismatch(PaymentAmountMismatchException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ValidationErrors.describe(ex)));
    }
}
