package com.procurepal_services.stock_bridge_api.selling.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One sale - every line recorded under its id - as it stands now.
 *
 * @param total what the lines that are not cancelled went for; lines with no price are left out
 *     and counted in {@code unpricedLineCount}, never added as zero.
 */
public record SaleResponse(
        UUID saleId,
        OffsetDateTime occurredAt,
        UUID recordedByUserId,
        String recordedByName,
        List<SaleLineResponse> lines,
        BigDecimal total,
        int lineCount,
        int cancelledLineCount,
        int unpricedLineCount) {
}
