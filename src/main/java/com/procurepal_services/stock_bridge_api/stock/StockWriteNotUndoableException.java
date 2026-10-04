package com.procurepal_services.stock_bridge_api.stock;

/**
 * A void (V39, decision D8) that would not put things back exactly, refused with the reason in
 * the person's own terms and what to do instead. Answered 409.
 */
public class StockWriteNotUndoableException extends RuntimeException {

    public StockWriteNotUndoableException(String message) {
        super(message);
    }
}
