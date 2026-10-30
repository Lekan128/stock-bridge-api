package com.procurepal_services.stock_bridge_api.branch.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One area of a branch (STOCK_AREAS_PLAN.md section 4), as {@code GET /api/branches} lists it under its
 * branch. A branch with one area has one of these, its Store, and the app shows no area word (rule 1).
 *
 * @param keepsStock false for an area that uses what it's given (D-A4): it never holds a figure.
 * @param delivery where deliveries arrive and waybills leave from; one per branch.
 * @param productsInStock how many products it holds any of - only for people who can manage branches,
 *     and only at a branch with more than one area (absent otherwise, like every null here).
 */
public record AreaResponse(
        UUID id,
        String name,
        boolean keepsStock,
        boolean delivery,
        boolean active,
        OffsetDateTime closedAt,
        Long productsInStock) {
}
