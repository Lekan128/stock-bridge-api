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
        Boolean clearCompanyVendor) {
}
