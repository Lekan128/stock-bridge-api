package com.procurepal_services.stock_bridge_api.settlement;

import java.util.UUID;

/**
 * The idempotency keys, formatted in one place so the two halves of the guard
 * cannot drift apart.
 *
 * <h2>What the key is FOR</h2>
 * Every trigger for a ledger write is repeatable: a buyer's receipt confirmation
 * can race the escrow sweep for the same order, an operator can click "reverse"
 * twice, a payout run can be retried after a timeout that had in fact committed.
 * The key turns each of those from "a vendor's balance silently doubles" into a
 * unique-index violation.
 *
 * <h2>Why the key names the EVENT and not the moment</h2>
 * {@code ACCRUAL:PROCEEDS:<orderItemId>} says "this line has accrued", which is
 * true exactly once for as long as the line exists. A key including a timestamp,
 * a batch id or the actor would be unique per ATTEMPT rather than per event, and
 * would therefore guard nothing at all - which is the way this pattern is usually
 * got wrong.
 *
 * <h2>Why a string rather than a compound unique index on (order_item_id, type)</h2>
 * A compound index would have worked for the four order-derived kinds and not for
 * {@code PAYOUT}, whose subject is a batch, so the table would have needed two
 * partial unique indexes and every new kind would have needed a third. One key
 * column covers every kind, present and future, and reads legibly in a database
 * console when somebody is trying to understand why an insert was refused.
 */
public final class VendorLedgerKeys {

    private VendorLedgerKeys() {
    }

    /** The proceeds owed for one sold line. Posted once, on confirmed delivery. */
    public static String accrualProceeds(UUID orderItemId) {
        return "ACCRUAL:PROCEEDS:" + orderItemId;
    }

    /** The platform's fee on one sold line. Posted with, and only with, the proceeds above. */
    public static String accrualCommission(UUID orderItemId) {
        return "ACCRUAL:COMMISSION:" + orderItemId;
    }

    /**
     * Undoing a line's proceeds after a refund, return or cancellation.
     *
     * <p>One reversal per line, ever. That is a deliberate limit and worth naming:
     * partial refunds are not modelled in this module, so a line is reversed in
     * full or not at all. A second, different reversal of the same line would need
     * a key that distinguished them, and the shape of that key should be decided by
     * whoever builds partial refunds rather than guessed at here.
     */
    public static String reversalProceeds(UUID orderItemId) {
        return "REVERSAL:PROCEEDS:" + orderItemId;
    }

    /** Undoing a line's commission. Always posted with {@link #reversalProceeds(UUID)}. */
    public static String reversalCommission(UUID orderItemId) {
        return "REVERSAL:COMMISSION:" + orderItemId;
    }

    /** The money leaving the bank for one batch. Subject is the batch, not an order. */
    public static String payout(UUID payoutBatchId) {
        return "PAYOUT:" + payoutBatchId;
    }
}
