package com.procurepal_services.stock_bridge_api.branch.dto;

import java.util.UUID;

/**
 * One product in one area (STOCK_AREAS_PLAN.md section 4.3): "Store 30 · Kitchen 6". Listed for every
 * open area that keeps stock, at a branch with more than one area - an area holding none reads 0, and
 * the app hides it.
 */
public record AreaFigure(UUID areaId, UUID branchId, int quantityOnHand) {
}
