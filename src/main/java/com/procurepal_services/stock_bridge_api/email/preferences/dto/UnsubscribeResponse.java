package com.procurepal_services.stock_bridge_api.email.preferences.dto;

/**
 * What {@code POST /api/email/unsubscribe} answers with. One field, one constant
 * value, and no relationship whatsoever to what actually happened in the database.
 *
 * <h2>Why it is a constant</h2>
 * The endpoint must not reveal whether an address is known to ProcurePal, so it
 * cannot report a row count, cannot say "you were already unsubscribed", and
 * cannot say "we have no record of that address". Any of those would turn a public
 * endpoint into a membership test against the customer list. Returning a body at
 * all - rather than 204 - is for the human who reaches this page through a mail
 * client that renders the response: silence reads as failure, and a reader who
 * thinks the unsubscribe failed presses "report spam", which is the outcome the
 * whole feature is trying to avoid.
 *
 * <p>The wording states the narrowing that {@code UnsubscribeService} documents:
 * marketing stops, receipts do not. Saying so here is not legal boilerplate, it is
 * how the reader learns their next order confirmation is still coming and does not
 * come back to complain that the unsubscribe was ignored.
 */
public record UnsubscribeResponse(String message) {

    private static final UnsubscribeResponse CONFIRMED = new UnsubscribeResponse(
            "You have been unsubscribed from ProcurePal marketing email. "
                    + "Order confirmations, delivery updates and account notices will still be sent.");

    /** The only instance any caller ever sees. */
    public static UnsubscribeResponse confirmed() {
        return CONFIRMED;
    }
}
