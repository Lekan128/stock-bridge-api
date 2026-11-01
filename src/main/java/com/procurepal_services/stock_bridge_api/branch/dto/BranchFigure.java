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
 * @param onTheWay sent to this branch on open waybills and not yet accounted for (UX section 5.9's
 *     "+ 20 on the way"). Not stock: never in {@code quantityOnHand}, never sellable (D-B8).
 */
public record BranchFigure(
        UUID branchId,
        int quantityOnHand,
        Integer lowStockThreshold,
        boolean isLowStock,
        StockStatus stockStatus,
        int onTheWay,
        /** The product page only (section 6): the branch's latest count; absent if never counted. */
        java.time.OffsetDateTime lastCountedAt,
        /** The product page only: the branch's own level, absent when it uses the product's. */
        Integer ownLowStockThreshold) {

    public static BranchFigure of(UUID branchId, int quantityOnHand, Integer lowStockThreshold) {
        return of(branchId, quantityOnHand, lowStockThreshold, 0);
    }

    public static BranchFigure of(UUID branchId, int quantityOnHand, Integer lowStockThreshold, int onTheWay) {
        return new BranchFigure(
                branchId,
                quantityOnHand,
                lowStockThreshold,
                StockStatus.isLowStock(quantityOnHand, lowStockThreshold),
                StockStatus.of(quantityOnHand, lowStockThreshold),
                onTheWay,
                null,
                null);
    }

    /** The same figure with the product page's two extras. */
    public BranchFigure withDetail(java.time.OffsetDateTime lastCounted, Integer ownLevel) {
        return new BranchFigure(branchId, quantityOnHand, lowStockThreshold, isLowStock, stockStatus, onTheWay, lastCounted, ownLevel);
    }
}
