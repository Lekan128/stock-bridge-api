package com.procurepal_services.stock_bridge_api.selling;

/** A branch price that can't be set or chosen, in a sentence that says why - answered 409. */
public class BranchPriceException extends RuntimeException {

    public BranchPriceException(String message) {
        super(message);
    }
}
