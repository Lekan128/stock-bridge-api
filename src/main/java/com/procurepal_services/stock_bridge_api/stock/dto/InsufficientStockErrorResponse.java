package com.procurepal_services.stock_bridge_api.stock.dto;

/**
 * The 409 oversell body - MULTI_VENDOR_INVENTORY_DESIGN.md section 8: "409/insufficient-stock
 * response states the actual available quantity rather than a bare rejection". A dedicated type
 * rather than adding fields to the flat {@code auth.ApiError} every other domain exception here
 * uses - {@code ApiError} is constructed in ~30 places across the codebase and this is the one
 * response that carries more than a message; the frontend's {@code createApiClient} already
 * expects exactly this shape (a {@code message} plus two optional numeric fields) alongside the
 * plain {@code {message}} envelope everything else returns.
 */
public record InsufficientStockErrorResponse(int status, String message, int availableQuantity, int requestedQuantity) {
}
