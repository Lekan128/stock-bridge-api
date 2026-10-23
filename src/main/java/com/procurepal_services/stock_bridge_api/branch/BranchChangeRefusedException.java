package com.procurepal_services.stock_bridge_api.branch;

/**
 * A branch change that the company's current state rules out, with the sentence that says why and what
 * to do instead (a name already used, a branch that still holds stock, the main branch). Maps to 409.
 */
public class BranchChangeRefusedException extends RuntimeException {

    public BranchChangeRefusedException(String message) {
        super(message);
    }
}
