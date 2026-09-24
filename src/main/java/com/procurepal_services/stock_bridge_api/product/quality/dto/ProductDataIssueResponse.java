package com.procurepal_services.stock_bridge_api.product.quality.dto;

import java.util.List;
import java.util.UUID;

/**
 * One product whose setup needs a look (BULK_IMPORT_CX_PLAN.md task 1.8).
 *
 * @param quantityOnHand what the product holds now, in whatever it is counted in - shown beside a
 *     missing unit, because choosing one decides what that number means.
 */
public record ProductDataIssueResponse(
        UUID productId, String name, String sku, int quantityOnHand, List<Issue> issues) {

    /**
     * @param code {@code DAMAGED_CODE}, {@code NO_STOCK_UNIT}, {@code UNIT_LOOKS_WRONG} or
     *     {@code PACK_LOOKS_TOO_SMALL}.
     * @param suggestedCode for {@code DAMAGED_CODE}: the code it most likely was, or null when the
     *     company generates codes (a new one is generated on fix).
     */
    public record Issue(String code, String message, String suggestedCode) {
    }
}
