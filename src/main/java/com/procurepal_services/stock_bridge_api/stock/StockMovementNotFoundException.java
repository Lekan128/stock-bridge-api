package com.procurepal_services.stock_bridge_api.stock;

/**
 * No {@code IN} {@code StockMovement} with that id belongs to the caller's tenant - covers
 * "does not exist", "belongs to another tenant" and "exists but is an OUT/ADJUSTMENT row"
 * alike, the same one-message-for-every-case reasoning {@code ProductVendorNotFoundException}
 * gives for its own 404. Used by {@code GET /api/stock-movements/{inMovementId}/allocations}.
 */
public class StockMovementNotFoundException extends RuntimeException {

    public StockMovementNotFoundException() {
        super("That delivery was not found.");
    }
}
