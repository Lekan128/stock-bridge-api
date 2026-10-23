package com.procurepal_services.stock_bridge_api.branch;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Branch refusals, for every controller that can run into one: the branch screens, and every place a
 * write names a branch (users, imports, expected deliveries, products) - not only {@code StockController},
 * which maps them itself. Lowest precedence, so a controller's own advice still wins.
 */
@RestControllerAdvice
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.LOWEST_PRECEDENCE)
public class BranchExceptionHandler {

    @ExceptionHandler(BranchNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(BranchNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler({BranchClosedException.class, BranchChangeRefusedException.class})
    public ResponseEntity<ApiError> handleConflict(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(BranchRequiredException.class)
    public ResponseEntity<ApiError> handleRequired(BranchRequiredException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
    }

    /** Not a branch refusal, but born of branches (UX section 5.14) and wanted on every product path. */
    @ExceptionHandler(com.procurepal_services.stock_bridge_api.selling.SellingPriceNotPermittedException.class)
    public ResponseEntity<ApiError> handlePriceNotPermitted(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(BranchNotAllowedException.class)
    public ResponseEntity<ApiError> handleNotAllowed(BranchNotAllowedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(com.procurepal_services.stock_bridge_api.transfer.TransferNotFoundException.class)
    public ResponseEntity<ApiError> handleTransferNotFound(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(com.procurepal_services.stock_bridge_api.transfer.TransferRefusedException.class)
    public ResponseEntity<ApiError> handleTransferRefused(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
    }
}
