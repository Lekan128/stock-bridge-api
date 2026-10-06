package com.procurepal_services.stock_bridge_api.onboarding;

/** A list file that can't be taken, with the reason in words the shop reads as they are. */
public class ProductListException extends RuntimeException {

    public ProductListException(String message) {
        super(message);
    }
}
