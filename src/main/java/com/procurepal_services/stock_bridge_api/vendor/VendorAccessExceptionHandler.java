package com.procurepal_services.stock_bridge_api.vendor;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Global, exactly like {@code MarketplaceAccessExceptionHandler} and for exactly
 * its reason: {@link VendorGuard} is called from controllers that will end up
 * spread across several feature packages (the vendor catalogue, the vendor order
 * queue, vendor sales analytics, the super-admin vendor surfaces), each with its
 * own feature-scoped advice. Scoping this one with {@code assignableTypes} would
 * mean listing every present and future vendor controller here, and the failure
 * mode for forgetting one is a 500 on an authorization check - the worst possible
 * place for a silent gap.
 *
 * <p>It is safe to be global for the same reason that one is: it handles a single
 * application-owned exception type with exactly one meaning. Unlike the
 * DataIntegrityViolationException handlers elsewhere, which must stay narrow so
 * they cannot mislabel an unrelated constraint violation.
 *
 * <p>403 rather than 404: the caller is authenticated and the route exists; their
 * account is simply the wrong kind for it.
 *
 * <h2>Two types now, both 403, and both global for the same reason</h2>
 * {@link VendorSingleAccountException} joins it because it is raised from the
 * user-management services rather than from any vendor controller - the tenant
 * one, the super-admin one, and whatever creates users next. Scoping it would
 * mean naming controllers in a package it does not live in, and the failure mode
 * for missing one is a 500 on an invariant check. Same argument, same answer.
 */
@RestControllerAdvice
public class VendorAccessExceptionHandler {

    @ExceptionHandler(VendorNotAllowedException.class)
    public ResponseEntity<ApiError> handleVendorNotAllowed(VendorNotAllowedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiError(ex.getMessage()));
    }

    /** See the exception for why 403 and not 409. */
    @ExceptionHandler(VendorSingleAccountException.class)
    public ResponseEntity<ApiError> handleSingleAccount(VendorSingleAccountException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiError(ex.getMessage()));
    }
}
