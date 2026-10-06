package com.procurepal_services.stock_bridge_api.founding;

import java.time.Duration;
import lombok.Getter;

@Getter
public class SetupRequestThrottledException extends RuntimeException {

    private final Duration retryAfter;

    public SetupRequestThrottledException(Duration retryAfter) {
        super("Too many requests from here just now. Wait a little and try again, or message us on WhatsApp.");
        this.retryAfter = retryAfter;
    }
}
