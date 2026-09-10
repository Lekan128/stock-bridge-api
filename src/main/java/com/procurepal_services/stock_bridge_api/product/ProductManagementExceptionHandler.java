package com.procurepal_services.stock_bridge_api.product;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.product.bulk.BulkUploadValidationException;
import com.procurepal_services.stock_bridge_api.product.bulk.ProductRowError;
import com.procurepal_services.stock_bridge_api.product.sku.InvalidSkuPatternException;
import com.procurepal_services.stock_bridge_api.product.sku.SkuGenerationExhaustedException;
import com.procurepal_services.stock_bridge_api.product.sku.SkuPatternTooLongException;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Scoped to ProductController only - see ClientSignupExceptionHandler for why. */
@RestControllerAdvice(assignableTypes = ProductController.class)
public class ProductManagementExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(ProductNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(SkuTakenException.class)
    public ResponseEntity<ApiError> handleSkuTaken(SkuTakenException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(SkuRequiredException.class)
    public ResponseEntity<ApiError> handleSkuRequired(SkuRequiredException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(SkuOverrideNotPermittedException.class)
    public ResponseEntity<ApiError> handleSkuOverrideNotPermitted(SkuOverrideNotPermittedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(InvalidSkuPatternException.class)
    public ResponseEntity<ApiError> handleInvalidSkuPattern(InvalidSkuPatternException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(SkuPatternTooLongException.class)
    public ResponseEntity<ApiError> handleSkuPatternTooLong(SkuPatternTooLongException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(SkuGenerationExhaustedException.class)
    public ResponseEntity<ApiError> handleSkuGenerationExhausted(SkuGenerationExhaustedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(InvalidProductVendorException.class)
    public ResponseEntity<ApiError> handleInvalidVendor(InvalidProductVendorException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(UnitPriceRequiredException.class)
    public ResponseEntity<ApiError> handleUnitPriceRequired(UnitPriceRequiredException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(InvalidUnitOfMeasureException.class)
    public ResponseEntity<ApiError> handleInvalidUnitOfMeasure(InvalidUnitOfMeasureException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(PackagingUnitAndSizeRequiredTogetherException.class)
    public ResponseEntity<ApiError> handlePackagingUnitAndSizeRequiredTogether(
            PackagingUnitAndSizeRequiredTogetherException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(PackagingRequiresUnitOfMeasureException.class)
    public ResponseEntity<ApiError> handlePackagingRequiresUnitOfMeasure(PackagingRequiresUnitOfMeasureException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(PackagingUnitSameAsStockUnitException.class)
    public ResponseEntity<ApiError> handlePackagingUnitSameAsStockUnit(PackagingUnitSameAsStockUnitException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
    }

    /** Safety net for a concurrent create()/update() racing the SKU pre-check. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("That SKU is already in use within this organization."));
    }

    /** Body is the raw error array (not wrapped) so the frontend can render it directly. */
    @ExceptionHandler(BulkUploadValidationException.class)
    public ResponseEntity<List<ProductRowError>> handleBulkUploadValidation(BulkUploadValidationException ex) {
        return ResponseEntity.badRequest().body(ex.getErrors());
    }
}
