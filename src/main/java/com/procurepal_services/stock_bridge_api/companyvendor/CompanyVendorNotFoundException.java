package com.procurepal_services.stock_bridge_api.companyvendor;

/**
 * No active directory entry with that id belongs to the caller's company. 404
 * covers both "deactivated" and "somebody else's", which is the whole point:
 * telling the two apart would confirm that another company's vendor id exists.
 */
public class CompanyVendorNotFoundException extends RuntimeException {

    public CompanyVendorNotFoundException() {
        super("That supplier was not found in your directory.");
    }
}
