package com.procurepal_services.stock_bridge_api.marketplace.analytics;

/**
 * A date range that cannot be answered: inverted, or wider than the module is willing to
 * scan. A 400 rather than a clamped-and-answered 200, because silently reporting a
 * different period than the one asked for is exactly how an analytics screen ends up
 * lying to the person reading it.
 */
public class InvalidAnalyticsRangeException extends RuntimeException {

    public InvalidAnalyticsRangeException(String message) {
        super(message);
    }
}
