package com.procurepal_services.stock_bridge_api.product.sku.dto;

/**
 * A non-committing peek at the next SKU - see {@code SkuGenerationService.preview}.
 *
 * @param nextSequence the raw counter value the peek was rendered from - the one part of the
 *     pattern the client cannot compute itself. The create-product form re-renders {@code sku}
 *     live, client-side, from this value plus the pattern (already fetched via {@code
 *     GET /api/products/sku-settings}) as the typed product name changes, rather than calling
 *     this endpoint again on every keystroke.
 */
public record SkuPreviewResponse(String sku, long nextSequence) {}
