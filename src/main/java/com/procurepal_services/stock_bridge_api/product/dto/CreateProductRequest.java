package com.procurepal_services.stock_bridge_api.product.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * <h2>V19: companyVendorId is gone; initialVendor replaces it</h2>
 * The old bare {@code companyVendorId} field assumed a product has at most one supplier - the
 * single-FK bottleneck MULTI_VENDOR_INVENTORY_DESIGN.md exists to remove. {@link #initialVendor}
 * is its replacement: optional, and when present it both links the new product to a supplier
 * from this company's OWN directory AND records the opening stock received from them, in one
 * screen - "full product form: name, SKU, base unit of measure, default packaging, then first
 * vendor + cost + quantity in the same screen (this IS the first ProductVendor row, not a
 * separate step)" (design doc section 7.1). When absent, product creation behaves exactly as
 * before V19: zero stock, no vendor - most buying companies logging their own napkin count
 * never fill this in, and nothing forces them to.
 *
 * <p>{@code companyVendorId} inside {@link InitialVendor} is resolved against the caller's OWN
 * tenant before it is stored, the same {@code CompanyVendorLookup} pattern
 * {@code ProductManagementService.resolveVendor}'s own javadoc warns is load-bearing - a vendor
 * id belonging to another company must be a field error, never a link.
 *
 * <h2>unitPrice is conditionally required, and not by an annotation</h2>
 * {@code @NotNull} does not appear on this field anymore, deliberately: whether a selling
 * price is required depends on WHO is calling, not on the shape of the request, and Bean
 * Validation cannot see that - it would need a lookup against {@code SellerDirectory} to know
 * whether this tenant is a buying company or a marketplace seller ({@code ClientType.VENDOR},
 * or the platform owner acting as one). That lookup already happens in
 * {@code ProductManagementService.create} to decide the initial moderation status, so the same
 * result answers this question too: a seller with no unitPrice is refused there
 * ({@code UnitPriceRequiredException}), while a buying company's unitPrice - if a stale client
 * still sends one - is silently discarded rather than stored, since a company has no selling
 * price for the field to mean.
 *
 * <h2>unitOfMeasure, packagingUnit and packagingSize: three fields, two independent axes</h2>
 * {@code unitOfMeasure} is what the product is fundamentally MEASURED in (a weight, volume,
 * length, or the generic "piece"); {@code packagingUnit} is how it is packaged/sold, if at
 * all (a Bag, Carton, Box); {@code packagingSize} says how many of {@code unitOfMeasure} one
 * {@code packagingUnit} holds. "A 50kg bag" is {@code unitOfMeasure="KG"},
 * {@code packagingUnit="BAG"}, {@code packagingSize=50} - all three together, not a bare
 * {@code unitOfMeasure="BAG"} with a count and no unit. Each is the CODE from the fixed
 * catalog in {@code product.unit.UnitOfMeasure} (e.g. {@code "KG"}, {@code "BAG"}), validated
 * with {@link com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasure#fromCode}
 * and stored as that code, not its display label - and role-checked: {@code unitOfMeasure}
 * must resolve to a {@code BASE}-role code, {@code packagingUnit} to a {@code PACKAGING}-role
 * one (see {@code product.unit.UnitOfMeasureRole}).
 *
 * <p>Two rules govern how the three combine, both enforced by the service layer rather than
 * Bean Validation (they are cross-field): {@code packagingUnit} and {@code packagingSize}
 * travel together - either both set or neither, never one alone - and whenever either is set,
 * {@code unitOfMeasure} must ALSO be set, since {@code packagingSize} is meaningless without
 * knowing what unit it is counting. {@code unitOfMeasure} alone, with no packaging, is fully
 * valid - a product sold loose, e.g. {@code unitOfMeasure="LITER"}. All three omitted is also
 * fine; nothing here is required. Open to EITHER tenant kind, unlike unitPrice - a buying
 * company logging its own stock benefits from a structured unit exactly as much as a seller
 * does.
 *
 * <h2>costPrice is gone - it is never a value a caller supplies</h2>
 * Per MULTI_VENDOR_INVENTORY_DESIGN.md section 5.3, {@code Product.costPrice} is a computed
 * weighted-average over purchase history (AVCO, same as Odoo/NetSuite), recalculated by
 * {@code StockManagementService.stockIn} on every stock-in - never a field a user types. A
 * bare {@code costPrice} argument here would have been redundant with {@link
 * InitialVendor#cost} at best (the weighted average of a starting quantity of 0 always
 * resolves to exactly the vendor's cost, so it was already silently overwritten whenever
 * {@code initialVendor} was present) and an unearned, unaudited number at worst (whenever it
 * was not). A product created with no {@code initialVendor} simply has no cost price at all
 * until its first real stock-in - the same "absent until purchased" state this field's
 * removal now makes the ONLY way to end up there.
 *
 * <h2>sku is conditionally required, and not by an annotation</h2>
 * {@code @NotBlank} does not appear on this field anymore, for the same reason it never
 * appeared on {@code unitPrice}: whether a SKU is required depends on a runtime tenant
 * setting ({@code ProductSkuSettingsService.isEnabled}), not on the shape of the request, and
 * Bean Validation cannot see that. {@code ProductManagementService.create} checks it: when
 * automatic SKU generation is on for the tenant, this field is ignored entirely (the server
 * generates and reserves the SKU itself - see {@code SkuGenerationService}); when it is off, a
 * blank value is rejected with {@code SkuRequiredException}, same as {@code @NotBlank} used to.
 */
public record CreateProductRequest(
        @NotBlank String name,
        String sku,
        String description,
        @DecimalMin(value = "0", inclusive = true) BigDecimal unitPrice,
        @Min(0) Integer lowStockThreshold,
        String unitOfMeasure,
        String packagingUnit,
        @DecimalMin(value = "0", inclusive = true) BigDecimal packagingSize,
        @Valid InitialVendor initialVendor) {

    /**
     * The product's first supplier line, filled in on the same screen as the product itself -
     * see the class javadoc. {@code quantity} is in base units of the product's own {@code
     * unitOfMeasure} (no separate {@code unit} toggle here, unlike {@code StockInRequest}: this
     * form is filling in the product's OWN base unit at the same time, so there is no
     * already-configured packaging unit yet to enter the quantity in). {@code packagingUnit}/
     * {@code packagingSize} describe THIS delivery and also seed the vendor line's own
     * defaults - see {@code ProductVendorService.findOrCreateForReceipt}.
     */
    public record InitialVendor(
            @NotNull UUID companyVendorId,
            String vendorSku,
            @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal cost,
            @NotNull @Positive Integer quantity,
            String packagingUnit,
            @DecimalMin(value = "0", inclusive = true) BigDecimal packagingSize) {
    }
}
