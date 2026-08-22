package com.procurepal_services.stock_bridge_api.product;

/**
 * The product's {@code companyVendorId} does not name an active entry in the
 * caller's OWN vendor directory. Maps to 400 with the field named, so the supplier
 * picker can say so rather than the save failing for a reason nobody can see.
 *
 * <p>Deliberately the same message whether the id is unknown, deactivated, or
 * belongs to another company - the last of those is the one that matters, and
 * distinguishing it would confirm that another company's vendor id exists. Same
 * reasoning as CompanyVendorNotFoundException answering 404 for both cases.
 */
public class InvalidProductVendorException extends RuntimeException {

    public InvalidProductVendorException() {
        super("That supplier is not in your vendor directory.");
    }
}
