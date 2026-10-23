package com.procurepal_services.stock_bridge_api.branch.dto;

import java.util.List;
import java.util.UUID;

/**
 * {@code GET /api/branches}: the branches this person sees, and what the app needs to decide how much
 * of the branch interface to show (MULTI_BRANCH_UX_PLAN.md section 4).
 *
 * @param multiBranch the company has, or has had, more than one branch. False means the app shows no
 *     branch word anywhere (rule 1).
 * @param seesEverywhere the person may open every branch and "All branches".
 * @param recordsEverywhere the person may record at every branch (owners, Work at all branches).
 * @param mayAddBranch the person may add one now: they can manage branches, and the rollout lets this
 *     company have a second.
 * @param sendTo every open branch, by name only, for the Send screen's "To" (UX section 5.4): a
 *     storekeeper sends to a branch they don't see. Empty for someone who can't send stock.
 */
public record BranchListResponse(
        List<BranchResponse> branches,
        boolean multiBranch,
        boolean seesEverywhere,
        boolean recordsEverywhere,
        boolean mayAddBranch,
        List<BranchName> sendTo) {

    public record BranchName(UUID id, String name) {
    }
}
