package com.procurepal_services.stock_bridge_api.product.sync;

/** A cursor this server did not issue. Maps to 400; the client starts a fresh full sync. */
public class InvalidSyncCursorException extends RuntimeException {

    public InvalidSyncCursorException() {
        super("This sync cursor is not valid. Start a full sync.");
    }
}
