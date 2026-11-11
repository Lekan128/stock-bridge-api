package com.procurepal_services.stock_bridge_api.branch.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * "The Kitchen needs 2 cartons of tomato paste. The Store has none." (STOCK_AREAS_PART_5.md): one line of an
 * open request its area said it has none of, for the people who buy (the Low stock page).
 */
public record NoneInStoreLine(
        UUID requestId,
        UUID branchId,
        UUID productId,
        String productName,
        String unitOfMeasure,
        BigDecimal quantity,
        BigDecimal enteredQuantity,
        String enteredUnit,
        String fromAreaName,
        String toAreaName,
        String askedByName,
        OffsetDateTime askedAt) {
}
