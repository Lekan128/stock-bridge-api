package com.procurepal_services.stock_bridge_api.branch.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One branch as the person asking sees it.
 *
 * @param main the main branch: where marketplace orders arrive and where people with no branches of
 *     their own work.
 * @param canRecord whether this person may record stock here now; false for a branch they only see
 *     (View all branches) and for a closed one.
 * @param productsInStock how many products it holds any of - only for people who can manage branches
 *     (absent otherwise, like every null here).
 * @param stockWorth what that stock cost, at each product's average cost - same audience.
 */
public record BranchResponse(
        UUID id,
        String name,
        String phone,
        boolean main,
        boolean active,
        OffsetDateTime closedAt,
        boolean canRecord,
        Long productsInStock,
        BigDecimal stockWorth) {
}
