package com.procurepal_services.stock_bridge_api.branch;

/**
 * A write at a branch the person doesn't work at - typically saved on a phone before their branches
 * changed (MULTI_BRANCH_UX_PLAN.md section 5.10). Maps to 403, like a permission taken away, so the
 * phone keeps it as "needs you" with this sentence and records nothing.
 */
public class BranchNotAllowedException extends RuntimeException {

    public BranchNotAllowedException(String branchName) {
        super("You don't work at " + branchName + ", so this wasn't recorded.");
    }
}
