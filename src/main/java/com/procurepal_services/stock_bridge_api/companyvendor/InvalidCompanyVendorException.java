package com.procurepal_services.stock_bridge_api.companyvendor;

/**
 * A field bean validation cannot judge on its own - today, the Nigerian state
 * list, mirroring InvalidDeliveryAddressException. Maps to 400.
 */
public class InvalidCompanyVendorException extends RuntimeException {

    public InvalidCompanyVendorException(String message) {
        super(message);
    }
}
