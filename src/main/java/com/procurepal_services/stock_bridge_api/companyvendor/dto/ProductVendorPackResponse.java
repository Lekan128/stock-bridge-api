package com.procurepal_services.stock_bridge_api.companyvendor.dto;

import com.procurepal_services.stock_bridge_api.entity.ProductVendorPack;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasure;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOptions;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One row of a Vendors-tab vendor's expanded pack list (MULTI_PACK_PER_VENDOR_DESIGN.md section
 * 7.1) - a {@code ProductVendorPack} together with its display label and price-break children.
 *
 * <h2>{@code lastCostPrice} is per STOCK UNIT, same as everywhere else</h2>
 * UNIT_UX_CONTRACT.md section 3.2 - per kg, not per bag - which is what makes this number
 * comparable to {@code Product.costPrice} and to another pack's price at all.
 *
 * <h2>{@code label} is section 1's Pack phrase, or the bare stock-unit symbol</h2>
 * {@code "Bag of 50 kg"} for a real pack; the product's stock-unit symbol ({@code "kg"}) for a
 * pack with no container - see {@code ProductVendorPack}'s class javadoc on what that means. This
 * is the ONLY rendering of a pack's container/size allowed on the wire, same rule
 * {@code UnitOptions.packLabel} states for itself.
 */
public record ProductVendorPackResponse(
        UUID id,
        String packagingUnit,
        BigDecimal packagingSize,
        String label,
        String vendorSku,
        BigDecimal lastCostPrice,
        boolean isDefault,
        List<ProductVendorPriceTierResponse> priceTiers,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static ProductVendorPackResponse from(
            ProductVendorPack pack, List<ProductVendorPriceTierResponse> priceTiers, String stockUnitCode) {
        String label = pack.getPackagingUnit() == null
                ? UnitOptions.symbolOf(stockUnitCode)
                : UnitOptions.packLabel(
                        UnitOfMeasure.fromCode(pack.getPackagingUnit())
                                .map(UnitOfMeasure::label)
                                .orElse(pack.getPackagingUnit()),
                        pack.getPackagingSize(),
                        stockUnitCode);
        return new ProductVendorPackResponse(
                pack.getId(),
                pack.getPackagingUnit(),
                pack.getPackagingSize(),
                label,
                pack.getVendorSku(),
                pack.getLastCostPrice(),
                pack.isDefault(),
                priceTiers,
                pack.getCreatedAt(),
                pack.getUpdatedAt());
    }
}
