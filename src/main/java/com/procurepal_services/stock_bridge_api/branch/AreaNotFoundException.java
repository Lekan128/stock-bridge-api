package com.procurepal_services.stock_bridge_api.branch;

/** A write named an area that isn't one of its branch's - another branch's, or another company's. 404. */
public class AreaNotFoundException extends RuntimeException {

    public AreaNotFoundException() {
        super("That area was not found at this branch.");
    }
}
