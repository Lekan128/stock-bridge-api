package com.procurepal_services.stock_bridge_api.selling;

/** A marketplace vendor asked to turn on selling from stock - answered 409 (plan decision 6). */
public class SellingNotAvailableException extends RuntimeException {

    public SellingNotAvailableException() {
        super("Selling from stock is for companies. As a marketplace seller, your products already have prices.");
    }

    /** The same refusal, with a sentence that fits where it came from. */
    public SellingNotAvailableException(String message) {
        super(message);
    }
}
