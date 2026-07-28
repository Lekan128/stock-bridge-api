package com.procurepal_services.stock_bridge_api.address;

/** A field bean validation cannot judge on its own - today, the Nigerian state list. Maps to 400. */
public class InvalidDeliveryAddressException extends RuntimeException {

    public InvalidDeliveryAddressException(String message) {
        super(message);
    }
}
