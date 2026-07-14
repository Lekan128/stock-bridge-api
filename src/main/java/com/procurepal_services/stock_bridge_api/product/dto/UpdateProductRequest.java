package com.procurepal_services.stock_bridge_api.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;

/**
 * All fields optional/nullable - only non-null ones are applied. removeImage
 * lets a caller clear image_url without providing a replacement file; it's
 * ignored if a new image file part is also present (the new upload wins).
 */
public record UpdateProductRequest(
        String name,
        String sku,
        String description,
        @DecimalMin(value = "0", inclusive = true) BigDecimal unitPrice,
        @DecimalMin(value = "0", inclusive = true) BigDecimal costPrice,
        @Min(0) Integer lowStockThreshold,
        Boolean active,
        Boolean removeImage) {
}
