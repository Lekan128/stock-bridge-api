package com.procurepal_services.stock_bridge_api.selling.dto;

import jakarta.validation.constraints.DecimalMin;

/**
 * The price for a sale line saved without one. One of the two: a price for each unit the line was
 * typed in ("₦2,400 a bottle"), or what the whole line went for. A line total is kept exactly.
 */
public record AddSalePriceRequest(
        @DecimalMin(value = "0", inclusive = true) java.math.BigDecimal unitPrice,
        @DecimalMin(value = "0", inclusive = true) java.math.BigDecimal lineTotal) {
}
