package com.procurepal_services.stock_bridge_api.selling;

/** A cancellation or price fix that cannot be made, with a sentence saying why - answered 409. */
public class SaleFixRefusedException extends RuntimeException {

    public SaleFixRefusedException(String message) {
        super(message);
    }
}
