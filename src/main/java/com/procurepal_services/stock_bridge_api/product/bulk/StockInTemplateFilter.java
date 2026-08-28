package com.procurepal_services.stock_bridge_api.product.bulk;

/**
 * Which slice of the catalog a stock-in template is generated for - the {@code filter} query
 * parameter of {@code GET /api/imports/templates/stock-in}, spelled exactly as
 * BULK_IMPORT_CONTRACT.md section 3 freezes it.
 *
 * <p>An explicit {@code productIds} list wins over any of these when present, because it comes
 * from the user having selected rows on the product list - a deliberate act that should never be
 * second-guessed by a filter default.
 */
public enum StockInTemplateFilter {

    /** Every active product. The Bulk actions to "Bulk stock in" entry point. */
    ALL,

    /**
     * Only products at or below their low-stock threshold. The single most useful filter, because
     * "what do we need to reorder" and "what did we just receive" are usually the same list a few
     * days apart.
     */
    LOW_STOCK,

    /** Products supplied by one vendor - one delivery from one supplier is the commonest shape of all. */
    BY_VENDOR,

    /** Products in one marketplace category. */
    BY_CATEGORY
}
