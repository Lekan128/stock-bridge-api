package com.procurepal_services.stock_bridge_api.branch.dto;

/**
 * STOCK_AREAS_BUILD_PLAN.md section 6 as numbers, across every company, for super admins. Run it after
 * deploying V60 and after every part of the areas work: {@link #healthy} must be true.
 *
 * @param branches every branch.
 * @param areaModeBranches branches that have ever had more than one area, so whose {@code area_stock}
 *     rows are the truth and whose {@code branch_stock} row is their sum (D-E2).
 * @param areas every area, closed ones included.
 * @param branchesWithoutOneOriginalArea must be 0: the original area holds a branch's history from
 *     before it had areas (D-E3), so there is exactly one.
 * @param branchesWithoutOneDeliveryArea must be 0: deliveries and waybills need exactly one place to
 *     arrive and leave from (D-E10).
 * @param areasOfAnotherCompany must be 0: an area whose company isn't its branch's.
 * @param movementsInAnotherBranchesArea must be 0: a ledger row in an area of a different branch.
 * @param usedAreasHoldingStock must be 0: an area that uses what it's given has no figure (D-E7).
 * @param areaSumMismatchProducts must be 0: (branch, product) figures at area-mode branches that are not
 *     the sum of their areas (D-E6). Moves waiting to be confirmed join the sum in Part 4.
 * @param healthy every must-be-zero figure is zero.
 */
public record AreaHealthResponse(
        long branches,
        long areaModeBranches,
        long areas,
        long branchesWithoutOneOriginalArea,
        long branchesWithoutOneDeliveryArea,
        long areasOfAnotherCompany,
        long movementsInAnotherBranchesArea,
        long usedAreasHoldingStock,
        long areaSumMismatchProducts,
        boolean healthy) {
}
