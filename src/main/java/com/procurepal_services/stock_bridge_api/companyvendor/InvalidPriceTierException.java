package com.procurepal_services.stock_bridge_api.companyvendor;

/**
 * A price-tier request failed a validation rule that is not row-local enough for the schema's
 * own CHECK constraints to catch on their own (a duplicate breakpoint on the SAME vendor line
 * is the schema's job via {@code uq_product_vendor_price_tiers_product_vendor_id_min_quantity};
 * this covers everything else - a non-positive minQuantity, a negative unitPrice). Maps to 400.
 */
public class InvalidPriceTierException extends RuntimeException {

    public InvalidPriceTierException(String message) {
        super(message);
    }
}
