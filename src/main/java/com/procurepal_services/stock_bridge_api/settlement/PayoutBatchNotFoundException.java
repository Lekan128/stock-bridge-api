package com.procurepal_services.stock_bridge_api.settlement;

import java.util.UUID;

/**
 * A payout batch id that resolves to nothing. Its own type rather than a reuse of
 * {@code ClientNotFoundException} for the reason {@code VendorNotFoundException}
 * gives: the operator who hits this is holding a batch id and a bank transfer they
 * are trying to record, and a message saying "Client not found" would send them to
 * the wrong table.
 */
public class PayoutBatchNotFoundException extends RuntimeException {

    public PayoutBatchNotFoundException(UUID id) {
        super("Payout batch " + id + " was not found.");
    }
}
