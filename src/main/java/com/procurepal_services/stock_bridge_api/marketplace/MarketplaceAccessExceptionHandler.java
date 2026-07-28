package com.procurepal_services.stock_bridge_api.marketplace;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The one deliberately GLOBAL @RestControllerAdvice in the application - every
 * other one is scoped with assignableTypes to a single controller.
 *
 * The reason it has to be global: PlatformOwnerGuard is called from marketplace
 * controllers spread across several feature packages (catalog admin, fulfilment
 * queue, marketplace analytics, marketplace settings), and each of those has its
 * own feature-scoped advice. Scoping this one the same way would mean listing every
 * present and future marketplace controller here, and the failure mode for
 * forgetting is a 500 on an authorization check - the worst possible place for a
 * silent gap.
 *
 * It is safe to be global precisely because it handles a single application-owned
 * exception type with exactly one meaning, unlike the DataIntegrityViolationException
 * handlers elsewhere, which must stay narrow so they don't mislabel an unrelated
 * constraint violation (see ClientSignupExceptionHandler).
 *
 * 403 rather than 404: the caller is authenticated and the route exists; they are
 * simply not the marketplace operator.
 */
@RestControllerAdvice
public class MarketplaceAccessExceptionHandler {

    @ExceptionHandler(PlatformOwnerNotAllowedException.class)
    public ResponseEntity<ApiError> handlePlatformOwnerNotAllowed(PlatformOwnerNotAllowedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiError(ex.getMessage()));
    }
}
