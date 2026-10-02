package com.procurepal_services.stock_bridge_api.product;

/**
 * The three-way cut `GET /api/products?stockStatus=` filters on and
 * `AnalyticsSummaryResponse`'s counts partition by — see
 * `UX_CONSISTENCY_DESIGN_PLAN.md` Pattern B ("every alarm view needs a census view").
 *
 * {@code OUT} is checked ahead of {@code LOW} wherever the two could both apply (zero on hand,
 * with a threshold set): a product with nothing left is reported as out of stock, not merely low,
 * so the three values partition the catalog with no product counted twice. This does not change
 * what {@code GET /api/products/low-stock} or {@code countLowStockByClientId} already mean — those
 * stay exactly as they were, for the page and card built on them before this existed.
 */
public enum StockStatus {
    /** {@code quantityOnHand <= 0}, regardless of whether a low-stock threshold is set. */
    OUT,
    /** {@code quantityOnHand > 0} and at or below a configured low-stock threshold. */
    LOW,
    /** Neither of the above — has stock, and it's above the alert line (or no alert is set). */
    OK,
}
