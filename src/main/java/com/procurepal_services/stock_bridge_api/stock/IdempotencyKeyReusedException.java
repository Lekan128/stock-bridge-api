package com.procurepal_services.stock_bridge_api.stock;

/**
 * The same {@code Idempotency-Key} arrived with a different request than the one it was first
 * used for. That is a client bug - a key names one intended write - so it is refused rather than
 * answered with the stored response to a request that asked for something else. Maps to 422.
 */
public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException() {
        super("This Idempotency-Key was already used for a different stock request.");
    }
}
