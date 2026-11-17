package com.procurepal_services.stock_bridge_api.product;

/** Something recorded for a product at a branch it isn't for (V92). Answered 409 by {@link MadeHereExceptionHandler}. */
public class ProductNotForBranchException extends RuntimeException {

    public ProductNotForBranchException(String productName, String branchName) {
        super(productName + " isn't for " + branchName + ". Add " + branchName + " to it on the product first.");
    }
}
