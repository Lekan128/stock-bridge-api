package com.procurepal_services.stock_bridge_api.analytics;

public class InvalidAnalyticsParameterException extends RuntimeException {

    public InvalidAnalyticsParameterException(String parameter, String value, String allowedValues) {
        super("Invalid " + parameter + " '" + value + "' - expected one of: " + allowedValues);
    }
}
