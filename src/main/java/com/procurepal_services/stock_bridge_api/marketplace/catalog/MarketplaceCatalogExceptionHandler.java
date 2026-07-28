package com.procurepal_services.stock_bridge_api.marketplace.catalog;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.ValidationErrors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Scoped to the two catalog controllers - see ClientSignupExceptionHandler for why these
 * are per-feature rather than global, and MarketplaceAccessExceptionHandler for the one
 * deliberate exception to that rule (the 403 from PlatformOwnerGuard, which is handled
 * globally and therefore does not appear here).
 */
@RestControllerAdvice(
        assignableTypes = {MarketplaceCatalogController.class, MarketplaceCatalogAdminController.class})
public class MarketplaceCatalogExceptionHandler {

    @ExceptionHandler(CatalogProductNotFoundException.class)
    public ResponseEntity<ApiError> handleProductNotFound(CatalogProductNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(CategoryNotFoundException.class)
    public ResponseEntity<ApiError> handleCategoryNotFound(CategoryNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler({CategorySlugTakenException.class, ProductSlugTakenException.class})
    public ResponseEntity<ApiError> handleSlugTaken(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
    }

    /**
     * 409, not 400: the request is well-formed and the operator is allowed to make it -
     * the catalog is simply in a state where it cannot be honoured yet. That distinction
     * is what tells the UI to offer "re-file these products" rather than "fix this field".
     */
    @ExceptionHandler(CategoryInUseException.class)
    public ResponseEntity<ApiError> handleCategoryInUse(CategoryInUseException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler({InvalidCategoryException.class, InvalidMarketplaceSettingsException.class})
    public ResponseEntity<ApiError> handleInvalidInput(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(ex.getMessage()));
    }

    /**
     * Safety net for two races the pre-checks cannot close: two admins saving a category
     * with the same slug at once (unique index on product_categories.slug), and two
     * listings deriving the same product slug at once (partial unique index on
     * products(client_id, slug)).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("That URL is already in use. Please choose a different one."));
    }

    /**
     * Handled here rather than left to Spring's default resolver - see
     * ProfileExceptionHandler for the /error re-dispatch problem that turns an unhandled
     * 400 into a confusing 403.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(ValidationErrors.describe(ex)));
    }

    /**
     * A query parameter that will not bind: {@code ?sort=CHEAPEST}, {@code ?ids=hello},
     * {@code ?minPrice=free}. This matters more on the public catalog than anywhere else
     * in the app, because those parameters come straight off a URL a visitor can edit or
     * a stale bookmark can carry, and the unhandled path turns them into a 500 - or, once
     * the /error re-dispatch is involved, into a 403 that reads as "you are not allowed to
     * shop here".
     *
     * The message names the parameter but never echoes the value, so a crafted query
     * cannot reflect content back through the error body.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("'" + ex.getName() + "' is not a valid value for this request."));
    }
}
