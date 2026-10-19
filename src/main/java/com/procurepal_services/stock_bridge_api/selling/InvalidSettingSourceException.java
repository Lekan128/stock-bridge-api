package com.procurepal_services.stock_bridge_api.selling;

/** A tenant request claimed to come from ProcurePal support - answered 400. */
public class InvalidSettingSourceException extends RuntimeException {

    public InvalidSettingSourceException() {
        super("Only ProcurePal support can make a change as support.");
    }
}
