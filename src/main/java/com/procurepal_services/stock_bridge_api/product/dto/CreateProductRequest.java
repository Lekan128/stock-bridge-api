package com.procurepal_services.stock_bridge_api.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@code companyVendorId} is optional and points at an entry in this company's OWN vendor
 * directory - it is resolved against the caller's tenant before it is stored, so a vendor id
 * belonging to another company is a field error rather than a link. Marketplace purchases set
 * it themselves; this is for stock sourced off-platform.
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
 */
public record CreateProductRequest(
        @NotBlank String name,
        @NotBlank String sku,
        String description,
        @DecimalMin(value = "0", inclusive = true) BigDecimal unitPrice,
        @DecimalMin(value = "0", inclusive = true) BigDecimal costPrice,
        @Min(0) Integer lowStockThreshold,
        UUID companyVendorId,
        String unitOfMeasure,
        String packagingUnit,
        @DecimalMin(value = "0", inclusive = true) BigDecimal packagingSize) {
}
