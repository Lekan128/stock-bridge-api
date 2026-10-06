package com.procurepal_services.stock_bridge_api.stock;

/** An {@code Idempotency-Key} header that is blank or too long to store. Maps to 400. */
public class InvalidIdempotencyKeyException extends RuntimeException {

    public InvalidIdempotencyKeyException(int maxLength) {
        super("Idempotency-Key must be between 1 and " + maxLength + " characters.");
    }
}
