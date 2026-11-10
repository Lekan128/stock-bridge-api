package com.procurepal_services.stock_bridge_api.product.sync;

import com.procurepal_services.stock_bridge_api.product.StockStatus;
import java.math.BigDecimal;
import com.procurepal_services.stock_bridge_api.branch.dto.BranchFigure;
import java.util.List;
import java.util.UUID;

/**
 * One product as the on-device catalogue stores it (A3): exactly what the Inventory list shows,
 * filters and sorts on, and nothing else. Costs, descriptions, suppliers and history stay on the
 * product page, which still asks the server for them.
 *
 * <p>{@code isLowStock} and {@code stockStatus} are computed here, by the same
 * {@link StockStatus} functions the list uses, so the phone never re-derives them and the two can
 * never disagree about which chip a product belongs under.
 */
public record ProductSyncRow(
        UUID id,
        String name,
        String sku,
        String barcode,
        String imageUrl,
        UUID categoryId,
        String categoryName,
        String unitOfMeasure,
        String packagingUnit,
        BigDecimal packagingSize,
        boolean hasMultiplePacks,
        BigDecimal unitPrice,
        BigDecimal quantityOnHand,
        int incomingQuantity,
        BigDecimal expectedQuantity,
        Integer lowStockThreshold,
        boolean active,
        boolean isLowStock,
        StockStatus stockStatus,
        // V47: the selling price as typed and its unit, so a phone selling offline pre-fills
        // "5,000 a carton" rather than 24 x 208.33. Absent when typed per stock unit.
        BigDecimal unitPriceEntered,
        String unitPriceEnteredUnit,
        // Multiple branches (V52): this product at each branch the person sees, so the phone can show
        // any of them offline (MULTI_BRANCH_UX_PLAN.md section 4.3). quantityOnHand and stockStatus
        // above stay the company's. Absent at a one-branch company.
        List<BranchFigure> branchStock,
        // Areas inside a branch (V60, STOCK_AREAS_PART_1.md): this product in each area of the branches
        // carried above that have more than one. Absent otherwise.
        List<com.procurepal_services.stock_bridge_api.branch.dto.AreaFigure> areaStock,
        // UNITS_PLAN.md Part 1-2 (V82): the product's own units and where its stock-outs and counts start, so the
        // phone offers "Derica" offline.
        List<com.procurepal_services.stock_bridge_api.product.unit.ProductMeasure> measures,
        String usedInUnit,
        // V84: the units a count of it opens in, so an offline count opens in them too. Absent: the default.
        List<String> countIn) {
}
