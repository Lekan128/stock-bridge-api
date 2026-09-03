package com.procurepal_services.stock_bridge_api.companyvendor.dto;

import com.procurepal_services.stock_bridge_api.entity.CompanyVendorKind;
import com.procurepal_services.stock_bridge_api.entity.ProductVendor;
import com.procurepal_services.stock_bridge_api.entity.ProductVendorPriceTier;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOption;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOptions;
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
 *
 * <h2>Every price on this row is per STOCK UNIT</h2>
 * {@link #lastCostPrice}, and every {@code unitPrice} inside {@link #priceTiers}, are money per
 * ONE of the product's {@code unitOfMeasure} - per kg, not per bag - as is
 * {@code ProductVendorPriceTier.minQuantity}'s quantity. UNIT_UX_CONTRACT.md section 3.2 pins
 * that, and it is what makes the numbers on this tab comparable to each other and to
 * {@code Product.costPrice} at all: before it, a tier row meant "at 500 kg, &#8358;44,000 per
 * bag" while the field beside it meant naira per kg, and the cheaper-vendor hint compared the
 * two (UNIT_UX_REMEDIATION_PLAN.md section 3, P0-2). A caller RENDERING these must state the
 * basis - non-negotiable 2 - and may convert to per-pack for display using {@link #unitOptions}
 * below, which carries the factor to divide by.
 *
 * <h2>unitOptions - the supplier-scoped unit set</h2>
 * Contract section 2.3: steps 1, 2, 3 and 4 of the section 2.1 algorithm - the product's stock
 * unit, the product's own pack, <b>this supplier's</b> pack when it differs, and the
 * same-category base units. This is the list a stock-in form switches its toggle to once a
 * supplier is chosen, because how goods arrive is a fact about the supplier as often as about
 * the product: the same rice comes from one mill in 50 kg bags and another in 25 kg bags.
 * {@code ProductResponse.unitOptions} is the same set minus step 3, for before a supplier is
 * known.
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
        List<UnitOption> unitOptions,
        List<ProductVendorPriceTierResponse> priceTiers,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /**
     * <p>Touches {@code vendor.getProduct()}'s unit fields to build {@link #unitOptions}, which
     * initialises that LAZY association. One initialisation for a whole Vendors tab, not one per
     * row - every line on the page hangs off the same product, so they share one proxy in the
     * persistence context - and the alternative (threading the three unit fields in as
     * parameters) would put the section 2.1 algorithm's inputs in the caller's hands, which is
     * how a second implementation of it starts.
     */
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
                UnitOptions.forProductAndSupplier(
                        vendor.getProduct().getUnitOfMeasure(),
                        vendor.getProduct().getPackagingUnit(),
                        vendor.getProduct().getPackagingSize(),
                        vendor.getDefaultPackagingUnit(),
                        vendor.getDefaultPackagingSize()),
                priceTiers.stream().map(ProductVendorPriceTierResponse::from).toList(),
                vendor.getCreatedAt(),
                vendor.getUpdatedAt());
    }
}
