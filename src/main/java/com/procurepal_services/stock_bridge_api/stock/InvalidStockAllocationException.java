package com.procurepal_services.stock_bridge_api.stock;

/**
 * A {@code stockOut} request's explicit {@code allocations} list (the "advanced" manual-override
 * path - MULTI_VENDOR_INVENTORY_DESIGN.md section 6/8) named an {@code inMovementId} that either
 * does not exist, does not belong to this product, is not an IN movement, or does not have
 * enough remaining balance to cover the requested quantity. Distinct from {@link
 * InsufficientStockException}, which is about the TOTAL requested quantity exceeding what is
 * available across every lot - this is about one specific lot line in the request being wrong.
 *
 * <p>Maps to 400, the same treatment {@code InsufficientStockException} gets.
 */
public class InvalidStockAllocationException extends RuntimeException {

    public InvalidStockAllocationException(String message) {
        super(message);
    }
}
