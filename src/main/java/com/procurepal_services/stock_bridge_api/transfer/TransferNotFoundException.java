package com.procurepal_services.stock_bridge_api.transfer;

/** A waybill that isn't this company's, or touches no branch the person sees. Maps to 404. */
public class TransferNotFoundException extends RuntimeException {

    public TransferNotFoundException() {
        super("That transfer was not found.");
    }
}
