package com.procurepal_services.stock_bridge_api.branch;

/**
 * A write named a branch that has been closed. Closing a branch keeps its history and takes it out of
 * every chooser (MULTI_BRANCH_UX_PLAN.md section 5.11), so only a phone that saved the write before
 * the branch closed can get here. Maps to 409: the same write was fine before it closed.
 */
public class BranchClosedException extends RuntimeException {

    public BranchClosedException() {
        super("That branch is closed, so nothing can be recorded there.");
    }
}
