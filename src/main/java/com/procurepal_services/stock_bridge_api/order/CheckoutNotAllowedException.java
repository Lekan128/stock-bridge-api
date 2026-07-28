package com.procurepal_services.stock_bridge_api.order;

/**
 * The basket cannot become an order: it is empty, a line is no longer purchasable,
 * the subtotal is under the marketplace minimum, there is no delivery address, or
 * pay-on-delivery was chosen without being eligible for it.
 *
 * One exception type with a specific message rather than one per rule, because the
 * caller does the same thing with all of them - shows the sentence next to a disabled
 * Place order button - and the quote endpoint already returns the full list of
 * reasons up front so this should only ever fire on a race.
 */
public class CheckoutNotAllowedException extends RuntimeException {

    public CheckoutNotAllowedException(String message) {
        super(message);
    }
}
