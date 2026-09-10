package com.procurepal_services.stock_bridge_api.imports.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

/**
 * Body of {@code POST /api/imports/{id}/rows/{rowId}/confirm-pack} - the review grid's one-click
 * "Confirm" on a {@code counted_in} cell that parsed as a deliberate size declaration
 * (MULTI_PACK_PER_VENDOR_DESIGN.md section 6a, error code {@code COUNTED_IN_NEW_PACK}). Both
 * fields come straight from that error's {@code suggestion} - the client does not invent them.
 */
public record ConfirmPackRequest(
        @NotBlank String packagingUnit, @DecimalMin(value = "0", inclusive = false) BigDecimal packagingSize) {
}
