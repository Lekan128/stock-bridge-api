package com.procurepal_services.stock_bridge_api.companyvendor.dto;

import com.procurepal_services.stock_bridge_api.entity.CompanyVendorKind;
import com.procurepal_services.stock_bridge_api.entity.ProductVendor;
import com.procurepal_services.stock_bridge_api.entity.ProductVendorPriceTier;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One row of the Vendors tab (MULTI_VENDOR_INVENTORY_DESIGN.md section 7.4) - a {@code
 * ProductVendor} line together with its associated {@code CompanyVendor}'s display facts
 * (name/kind/active) and its price-break children.
 *
 * <h2>{@code id} is this row's OWN id, not {@code companyVendorId}</h2>
 * The frontend addresses {@code PATCH .../vendors/{vendorId}} and the price-tier endpoints by
 * THIS id - the same "address a resource by its own primary key" convention the rest of this
 * API already uses - never by {@link #companyVendorId}, a different id also present on the row
 * for display/linking purposes only.
 *
 * <h2>priceTiers is always populated here</h2>
 * Unlike {@code ProductVendor.priceTiers}' own LAZY association (never touched directly), every
 * caller building this response passes an already-batch-loaded list - see {@code
 * ProductVendorController} - so a page of vendors costs one extra query total, not one per row.
 */
public record ProductVendorResponse(
        UUID id,
        UUID productId,
        UUID companyVendorId,
        String companyVendorName,
        CompanyVendorKind companyVendorKind,
        boolean companyVendorActive,
        String vendorSku,
        BigDecimal lastCostPrice,
        String defaultPackagingUnit,
        BigDecimal defaultPackagingSize,
        boolean isPreferred,
        int quantityOnHandFromVendor,
        int totalQuantityReceived,
        List<ProductVendorPriceTierResponse> priceTiers,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static ProductVendorResponse from(ProductVendor vendor, List<ProductVendorPriceTier> priceTiers) {
        return new ProductVendorResponse(
                vendor.getId(),
                vendor.getProduct().getId(),
                vendor.getCompanyVendor().getId(),
                vendor.getCompanyVendor().getName(),
                vendor.getCompanyVendor().getVendorKind(),
                vendor.getCompanyVendor().isActive(),
                vendor.getVendorSku(),
                vendor.getLastCostPrice(),
                vendor.getDefaultPackagingUnit(),
                vendor.getDefaultPackagingSize(),
                vendor.isPreferred(),
                vendor.getQuantityOnHandFromVendor(),
                vendor.getTotalQuantityReceived(),
                priceTiers.stream().map(ProductVendorPriceTierResponse::from).toList(),
                vendor.getCreatedAt(),
                vendor.getUpdatedAt());
    }
}
