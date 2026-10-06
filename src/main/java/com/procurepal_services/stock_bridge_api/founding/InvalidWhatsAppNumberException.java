package com.procurepal_services.stock_bridge_api.founding;

public class InvalidWhatsAppNumberException extends RuntimeException {

    public InvalidWhatsAppNumberException() {
        super("Enter a Nigerian mobile number, like 0803 123 4567.");
    }
}
