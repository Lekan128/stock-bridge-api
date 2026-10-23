package com.procurepal_services.stock_bridge_api.branch;

/**
 * A write named a branch that is not this company's. The same answer whether it belongs to another
 * company or to none, so the message says nothing about which. Maps to 404, like a product not found.
 */
public class BranchNotFoundException extends RuntimeException {

    public BranchNotFoundException() {
        super("That branch was not found.");
    }
}
