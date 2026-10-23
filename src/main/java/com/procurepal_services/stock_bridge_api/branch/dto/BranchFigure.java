package com.procurepal_services.stock_bridge_api.branch.dto;

import com.procurepal_services.stock_bridge_api.product.StockStatus;
import java.util.UUID;

/**
 * One product at one branch - the phone's Inventory list, chips and product page (MULTI_BRANCH_UX_PLAN.md
 * sections 4.3 and 5.9). Every branch the person sees is listed, a branch holding none of it as 0.
 *
 * @param lowStockThreshold this branch's own level, or the product's when the branch has none.
 * @param isLowStock / {@code stockStatus} computed by {@link StockStatus}, as the company figure is,
 *     so the phone never re-derives them.
 */
public record BranchFigure(
        UUID branchId, int quantityOnHand, Integer lowStockThreshold, boolean isLowStock, StockStatus stockStatus) {

    public static BranchFigure of(UUID branchId, int quantityOnHand, Integer lowStockThreshold) {
        return new BranchFigure(
                branchId,
                quantityOnHand,
                lowStockThreshold,
                StockStatus.isLowStock(quantityOnHand, lowStockThreshold),
                StockStatus.of(quantityOnHand, lowStockThreshold));
    }
}
