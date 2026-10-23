package com.procurepal_services.stock_bridge_api.branch.dto;

/**
 * MULTI_BRANCH_BUILD_PLAN.md section 3.3 as numbers, across every company, for super admins. Run it
 * after deploying V51 and before turning anything on for a company: {@link #healthy} must be true.
 *
 * @param companies every company.
 * @param multiBranchCompanies companies that have ever had more than one branch, so whose
 *     {@code branch_stock} rows are the truth (see {@code BranchStockService}).
 * @param companiesWithoutDefaultBranch must be 0: every company has one, and a write that finds none
 *     creates it.
 * @param movementsAtAnotherCompanysBranch must be 0: a ledger row at a branch its company doesn't own.
 * @param totalMismatchProducts must be 0: products of multi-branch companies whose company figure is
 *     not the sum of their branches.
 * @param mirrorDriftProducts informational: products of one-branch companies whose branch row has
 *     fallen behind the product's figure (set outside the ledger). Harmless while the company has one
 *     branch, repaired by the product's next write, and copied over whole when a second branch is
 *     added.
 * @param ledgerDriftProducts informational: (product, branch) figures that the ledger alone doesn't
 *     add up to. Expected for stock that predates the ledger (before V19/V20); a number that grows
 *     after V51 points at a write path that skipped the ledger.
 * @param healthy the three must-be-zero figures are zero.
 */
public record BranchHealthResponse(
        long companies,
        long multiBranchCompanies,
        long companiesWithoutDefaultBranch,
        long movementsAtAnotherCompanysBranch,
        long totalMismatchProducts,
        long mirrorDriftProducts,
        long ledgerDriftProducts,
        boolean healthy) {
}
