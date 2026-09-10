package com.procurepal_services.stock_bridge_api.companyvendor;

/** No price-break row with that id belongs to the vendor line named in the URL. Maps to 404. */
public class PriceTierNotFoundException extends RuntimeException {

    public PriceTierNotFoundException() {
        super("That price tier was not found on this supplier.");
    }
}
