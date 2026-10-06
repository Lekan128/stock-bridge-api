package com.procurepal_services.stock_bridge_api.founding;

public class SetupRequestNotFoundException extends RuntimeException {

    public SetupRequestNotFoundException() {
        super("That setup request does not exist.");
    }
}
