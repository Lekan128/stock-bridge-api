package com.procurepal_services.stock_bridge_api.address;

/** No active address with that id belongs to the caller's company. 404 covers both "gone" and "not yours". */
public class DeliveryAddressNotFoundException extends RuntimeException {

    public DeliveryAddressNotFoundException() {
        super("That delivery address was not found.");
    }
}
