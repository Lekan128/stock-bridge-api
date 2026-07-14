package com.procurepal_services.stock_bridge_api.stock;

public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(int available, int requested) {
        super("Cannot remove " + requested + " unit(s) - only " + available + " in stock");
    }
}
