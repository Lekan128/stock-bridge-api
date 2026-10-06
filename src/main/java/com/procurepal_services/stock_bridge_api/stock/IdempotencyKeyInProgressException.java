package com.procurepal_services.stock_bridge_api.stock;

/**
 * A request with this {@code Idempotency-Key} exists but has no stored response to replay. Not
 * reachable in normal operation (see {@link StockIdempotencyService}); maps to 409 so a client
 * simply retries.
 */
public class IdempotencyKeyInProgressException extends RuntimeException {

    public IdempotencyKeyInProgressException() {
        super("A request with this Idempotency-Key is still being processed. Try again shortly.");
    }
}
