package com.procurepal_services.stock_bridge_api.branch;

/** A write named an area that has been closed. Its history stays; nothing new is recorded there. 409. */
public class AreaClosedException extends RuntimeException {

    public AreaClosedException(String areaName) {
        super(areaName + " is closed. Choose another area.");
    }
}
