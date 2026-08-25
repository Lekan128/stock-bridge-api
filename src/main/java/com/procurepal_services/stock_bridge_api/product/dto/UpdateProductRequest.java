package com.procurepal_services.stock_bridge_api.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * All fields optional/nullable - only non-null ones are applied. removeImage
 * lets a caller clear image_url without providing a replacement file; it's
 * ignored if a new image file part is also present (the new upload wins).
 *
 * <p>{@code clearCompanyVendor} exists for the same reason removeImage does, and it is not
 * redundant with sending {@code companyVendorId: null}: in a patch-style DTO null means "not
 * provided", so there would otherwise be no way to say "unlink this product from its supplier"
 * at all. The explicit flag wins over companyVendorId if both are sent, because "clear it" is
 * the less ambiguous of two contradictory instructions.
 *
 * <h2>unitPrice: patch semantics, but a seller may never end up with none</h2>
 * A non-null unitPrice is applied exactly like every other patch field here - but for a
 * SELLER's product ({@code ClientType.VENDOR}, or the platform owner acting as one),
 * {@code ProductManagementService.update} then checks the RESULTING row: if it is a seller's
 * product and {@code unitPrice} is still null after this update - whether because the row
 * already had none and this request did not supply one, or because it never had one to begin
 * with - that fails with {@code UnitPriceRequiredException}. A non-seller's unitPrice, if a
 * stale client still sends one, is discarded rather than applied or rejected; see
 * {@link CreateProductRequest} for the full reasoning, which applies here unchanged.
 *
 * <h2>unitOfMeasure, packagingUnit, packagingSize: patch semantics, checked on the result</h2>
 * Same non-null-is-applied rule as every other field here, and the same three-field model as
 * {@link CreateProductRequest}: {@code unitOfMeasure} is what the product is measured in,
 * {@code packagingUnit}/{@code packagingSize} say how it is packaged and how much one package
 * holds. Both cross-field rules are checked against the RESULTING state after whichever of the
 * three this request supplies is applied - not the request in isolation - which is what makes
 * them correct for this DTO's patch semantics: a request supplying only one field of a pair is
 * fine as long as the product already carries the other from before.
 *
 * <ul>
 *   <li>{@code packagingUnit} and {@code packagingSize} must end up both set or both unset.
 *       Sending a blank {@code packagingUnit} clears it to null (matching how brand and unit
 *       of measure have always behaved on the marketplace-details route), so clearing a
 *       previously-paired packaging unit without also clearing its size runs straight into
 *       this check.</li>
 *   <li>Whenever the resulting {@code packagingUnit}/{@code packagingSize} are set,
 *       {@code unitOfMeasure} must also be non-null in the resulting state - clearing
 *       {@code unitOfMeasure} while packaging is still in place from before is rejected the
 *       same way as never having set a {@code unitOfMeasure} at all.</li>
 * </ul>
 */
public record UpdateProductRequest(
        String name,
        String sku,
        String description,
        @DecimalMin(value = "0", inclusive = true) BigDecimal unitPrice,
        @DecimalMin(value = "0", inclusive = true) BigDecimal costPrice,
        @Min(0) Integer lowStockThreshold,
        Boolean active,
        Boolean removeImage,
        UUID companyVendorId,
        Boolean clearCompanyVendor,
        String unitOfMeasure,
        String packagingUnit,
        @DecimalMin(value = "0", inclusive = true) BigDecimal packagingSize) {
}
