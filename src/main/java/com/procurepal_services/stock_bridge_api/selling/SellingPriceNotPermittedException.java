package com.procurepal_services.stock_bridge_api.selling;

/** A selling price change by somebody without {@code CHANGE_SELLING_PRICES}. Maps to 403. */
public class SellingPriceNotPermittedException extends RuntimeException {

    public SellingPriceNotPermittedException() {
        super("Prices are set by people who can change selling prices.");
    }
}
