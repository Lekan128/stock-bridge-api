package com.procurepal_services.stock_bridge_api.expected;

import org.springframework.http.HttpStatus;

/** An expected-delivery request that cannot be done, with the status and the sentence to say why. */
public class ExpectedDeliveryException extends RuntimeException {

    private final HttpStatus status;

    public ExpectedDeliveryException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    static ExpectedDeliveryException notFound() {
        return new ExpectedDeliveryException(HttpStatus.NOT_FOUND, "We couldn't find that expected delivery.");
    }

    static ExpectedDeliveryException noSuchProduct() {
        return new ExpectedDeliveryException(
                HttpStatus.BAD_REQUEST, "One of these products is no longer in your catalog.");
    }

    static ExpectedDeliveryException noSuchSupplier() {
        return new ExpectedDeliveryException(HttpStatus.BAD_REQUEST, "We couldn't find that supplier.");
    }

    /** The unit a line was ordered in is not one this product is bought in (any more). */
    static ExpectedDeliveryException unknownUnit(String productName, String countedIn) {
        return new ExpectedDeliveryException(
                HttpStatus.BAD_REQUEST,
                productName + " isn't bought in " + countedIn + ". Pick one of the ways you buy it.");
    }

    static ExpectedDeliveryException alreadyClosed(String what) {
        return new ExpectedDeliveryException(
                HttpStatus.CONFLICT, "This expected delivery is already " + what + ".");
    }

    // There is deliberately no "that date is in the past" rule. A delivery that should have come
    // last Tuesday and has not is exactly what this feature exists to show, and an expectation
    // typed in after the fact is the normal way one gets recorded at all.
}
