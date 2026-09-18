package com.procurepal_services.stock_bridge_api.product;

/**
 * Two products in one company cannot carry the same barcode - scanning is only worth anything if a
 * scan lands on exactly one product (BULK_IMPORT_CX_PLAN.md task 3.3). The message names the
 * product that already has it, because the useful next action is almost always to go and look at
 * that one rather than to invent a different code.
 */
public class BarcodeTakenException extends RuntimeException {

    public BarcodeTakenException(String barcode, String productName) {
        super(productName == null
                ? "Barcode " + barcode + " is already on another product."
                : "Barcode " + barcode + " is already on " + productName + ".");
    }
}
