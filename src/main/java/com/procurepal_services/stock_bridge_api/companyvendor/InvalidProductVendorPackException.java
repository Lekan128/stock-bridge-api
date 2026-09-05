package com.procurepal_services.stock_bridge_api.companyvendor;

/**
 * A pack request failed a validation rule that is not row-local enough for the schema's own
 * CHECK/UNIQUE constraints to catch on their own - a non-positive size, a container with no size
 * (or vice versa), or a duplicate (container, size) / duplicate bare-stock-unit pack on the SAME
 * vendor line, surfaced here with a message naming the conflict rather than a raw constraint
 * violation. Maps to 400.
 */
public class InvalidProductVendorPackException extends RuntimeException {

    public InvalidProductVendorPackException(String message) {
        super(message);
    }
}
