package com.procurepal_services.stock_bridge_api.product.sku;

/** A pattern that fails {@link SkuPatternValidator#validate}'s grammar or SEQ-cardinality rules. */
public class InvalidSkuPatternException extends RuntimeException {

    public InvalidSkuPatternException(String message) {
        super(message);
    }
}
