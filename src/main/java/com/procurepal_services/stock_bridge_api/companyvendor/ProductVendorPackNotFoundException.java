package com.procurepal_services.stock_bridge_api.companyvendor;

/** No pack row with that id belongs to the vendor line named in the URL. Maps to 404. */
public class ProductVendorPackNotFoundException extends RuntimeException {

    public ProductVendorPackNotFoundException() {
        super("That pack was not found on this supplier.");
    }
}
