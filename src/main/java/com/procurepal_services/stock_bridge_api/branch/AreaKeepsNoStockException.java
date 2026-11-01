package com.procurepal_services.stock_bridge_api.branch;

/**
 * A write named an area that uses what it is given (STOCK_AREAS_PLAN.md D-A4): it has no figure, so
 * there is nothing there to take stock out of, count or deliver into. Stock reaches it by being moved
 * there (Part 2). 409.
 */
public class AreaKeepsNoStockException extends RuntimeException {

    public AreaKeepsNoStockException(String areaName) {
        super(areaName + " uses stock up as soon as it arrives, so it has no stock to record against.");
    }
}
