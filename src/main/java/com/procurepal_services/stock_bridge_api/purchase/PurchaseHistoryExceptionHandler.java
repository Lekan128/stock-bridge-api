package com.procurepal_services.stock_bridge_api.purchase;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.companyvendor.CompanyVendorNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Scoped to PurchaseHistoryController only (via assignableTypes), matching every other module's
 * own handler - see ClientSignupExceptionHandler for why a shared/global one would be the wrong
 * call. {@link CompanyVendorNotFoundException} is reused rather than redeclared: a
 * {@code companyVendorId} filter that is not this tenant's own is exactly the same fact
 * {@code CompanyVendorLookup} already names everywhere else it is used, and a second exception
 * type for the identical condition would just be two ways to say one thing.
 */
@RestControllerAdvice(assignableTypes = PurchaseHistoryController.class)
public class PurchaseHistoryExceptionHandler {

    @ExceptionHandler(CompanyVendorNotFoundException.class)
    public ResponseEntity<ApiError> handleVendorNotFound(CompanyVendorNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage()));
    }
}
