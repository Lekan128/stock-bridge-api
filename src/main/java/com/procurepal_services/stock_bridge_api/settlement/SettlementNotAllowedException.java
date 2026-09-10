package com.procurepal_services.stock_bridge_api.settlement;

/**
 * A settlement action the caller is entitled to make, refused because of the STATE
 * of the rows rather than because of who is asking. Mapped to 409, matching
 * {@code VendorApplicationAlreadyReviewedException} and {@code LastActiveOwnerException}:
 * the request was well-formed and the caller is allowed to make it, but another
 * operator got there first or the data is not in a shape where it means anything.
 *
 * <p>Every use is a money action somebody is about to repeat by refreshing, so the
 * messages say what already happened rather than only that something is wrong:
 * "already run for this period", "already marked paid", "nothing has accrued on
 * this order". A vague 409 on a payout screen gets clicked again.
 */
public class SettlementNotAllowedException extends RuntimeException {

    public SettlementNotAllowedException(String message) {
        super(message);
    }
}
