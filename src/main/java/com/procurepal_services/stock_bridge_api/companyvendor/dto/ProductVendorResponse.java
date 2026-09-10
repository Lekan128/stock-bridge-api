package com.procurepal_services.stock_bridge_api.companyvendor.dto;

import com.procurepal_services.stock_bridge_api.entity.CompanyVendorKind;
import com.procurepal_services.stock_bridge_api.entity.ProductVendor;
import com.procurepal_services.stock_bridge_api.entity.ProductVendorPack;
import com.procurepal_services.stock_bridge_api.entity.ProductVendorPriceTier;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOption;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOptions;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One row of the Vendors tab (MULTI_VENDOR_INVENTORY_DESIGN.md section 7.4) - a {@code
 * ProductVendor} line together with its associated {@code CompanyVendor}'s display facts
 * (name/kind/active) and its pack children (MULTI_PACK_PER_VENDOR_DESIGN.md sections 4-7).
 *
 * <h2>{@code id} is this row's OWN id, not {@code companyVendorId}</h2>
 * The frontend addresses {@code PATCH .../vendors/{vendorId}} and the pack/price-tier endpoints
 * by THIS id - the same "address a resource by its own primary key" convention the rest of this
 * API already uses - never by {@link #companyVendorId}, a different id also present on the row
 * for display/linking purposes only.
 *
 * <h2>{@code packs} is always populated here</h2>
 * Unlike {@code ProductVendor.packs}' own LAZY association (never touched directly), every
 * caller building this response passes an already-batch-loaded list - see {@code
 * ProductVendorController} - so a page of vendors costs a small, fixed number of extra queries
 * total, not one per row.
 *
 * <h2>{@code vendorSku}/{@code lastCostPrice}/{@code defaultPackagingUnit}/
 * {@code defaultPackagingSize} are a permanent alias for the default pack (V24)</h2>
 * These four fields moved onto {@link ProductVendorPack} - see that class and
 * MULTI_PACK_PER_VENDOR_DESIGN.md section 4.2 - but stay on this response, mirroring whichever
 * pack in {@link #packs} has {@code isDefault = true} (or every field {@code null} when this
 * vendor has no pack on file at all), the same "old spelling accepted forever" discipline the
 * bulk-import column renames already use. A caller reading a specific pack's own price should
 * read {@link #packs} directly; these four exist for anything not yet updated to.
 *
 * <h2>unitOptions - the supplier-scoped unit set</h2>
 * Contract section 2.3: steps 1, 2, 3 and 4 of the section 2.1 algorithm - the product's stock
 * unit, the product's own pack, EVERY ONE of this supplier's real packs that differs, and the
 * same-category base units. This is the list a stock-in form switches its toggle to once a
 * supplier is chosen, because how goods arrive is a fact about the supplier as often as about
 * the product: the same rice comes from one mill in 50 kg bags and another in 25 kg bags - and,
 * as of this table, possibly both from the SAME mill. {@code ProductResponse.unitOptions} is the
 * same set minus step 3, for before a supplier is known.
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
        List<ProductVendorPackResponse> packs,
        List<UnitOption> unitOptions,
        List<ProductVendorPriceTierResponse> priceTiers,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /**
     * @param packs this vendor's packs, in any order - {@link #packs()} on the response is sorted
     *     default-first by the caller's query (see {@code ProductVendorPackRepository}).
     * @param tiersByPackId every one of this vendor's packs' price tiers, keyed by pack id -
     *     batch-loaded by the caller for the same N+1 reason {@link #packs} itself is.
     */
    public static ProductVendorResponse from(
            ProductVendor vendor, List<ProductVendorPack> packs, Map<UUID, List<ProductVendorPriceTier>> tiersByPackId) {
        String stockUnitCode = vendor.getProduct().getUnitOfMeasure();

        List<ProductVendorPackResponse> packResponses = packs.stream()
                .map(pack -> ProductVendorPackResponse.from(
                        pack,
                        tiersByPackId.getOrDefault(pack.getId(), List.of()).stream()
                                .map(ProductVendorPriceTierResponse::from)
                                .toList(),
                        stockUnitCode))
                .toList();

        ProductVendorPack defaultPack = packs.stream().filter(ProductVendorPack::isDefault).findFirst().orElse(null);

        // priceTiers (top-level) mirrors the DEFAULT pack's tiers only, the same alias-not-
        // duplication treatment vendorSku/lastCostPrice below get - a non-default pack's tiers
        // are read through packs[].priceTiers.
        List<ProductVendorPriceTierResponse> defaultPackTiers = defaultPack == null
                ? List.of()
                : tiersByPackId.getOrDefault(defaultPack.getId(), List.of()).stream()
                        .map(ProductVendorPriceTierResponse::from)
                        .toList();

        return new ProductVendorResponse(
                vendor.getId(),
                vendor.getProduct().getId(),
                vendor.getCompanyVendor().getId(),
                vendor.getCompanyVendor().getName(),
                vendor.getCompanyVendor().getVendorKind(),
                vendor.getCompanyVendor().isActive(),
                defaultPack == null ? null : defaultPack.getVendorSku(),
                defaultPack == null ? null : defaultPack.getLastCostPrice(),
                defaultPack == null ? null : defaultPack.getPackagingUnit(),
                defaultPack == null ? null : defaultPack.getPackagingSize(),
                vendor.isPreferred(),
                vendor.getQuantityOnHandFromVendor(),
                vendor.getTotalQuantityReceived(),
                packResponses,
                UnitOptions.forProductAndSupplier(
                        stockUnitCode,
                        vendor.getProduct().getPackagingUnit(),
                        vendor.getProduct().getPackagingSize(),
                        packs.stream()
                                .map(pack -> new UnitOptions.PackSpec(pack.getPackagingUnit(), pack.getPackagingSize()))
                                .toList()),
                defaultPackTiers,
                vendor.getCreatedAt(),
                vendor.getUpdatedAt());
    }
}
