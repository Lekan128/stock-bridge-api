package com.procurepal_services.stock_bridge_api.entity;

/**
 * Where a payout batch is in its short life. There is no automated bank
 * disbursement in this module and none is planned here, so both terminal states
 * are reached by a human telling us what happened at the bank.
 *
 * <p>The states are not a fulfilment-style state machine with a transition table,
 * because there are only two moves and both leave the batch terminal. What matters
 * is what each one does to the ledger, and the asymmetry is the interesting part:
 *
 * <ul>
 *   <li>{@link #PAID} posts a {@code PAYOUT} entry - money genuinely left the
 *       bank, so the ledger records it and the vendor's balance falls.</li>
 *   <li>{@link #FAILED} posts NOTHING, and instead deletes the batch's membership
 *       rows so its lines are eligible again at the next run. No money moved, so
 *       no ledger entry should claim any. See {@code vendor_payout_batch_lines} in
 *       V14 for why deleting there is legitimate when deleting in the ledger never
 *       is.</li>
 * </ul>
 */
public enum VendorPayoutBatchStatus {

    /** Computed and awaiting a human making the transfer. Its lines are claimed but unpaid. */
    PENDING,

    /** A human made the transfer and recorded doing so. The batch carries who, when and the bank reference. */
    PAID,

    /**
     * The transfer did not happen. Carries a reason, required by
     * {@code chk_vendor_payout_batches_failure_shape} - a batch that failed with no
     * stated reason is indistinguishable from one somebody clicked by accident, and
     * its lines are about to be released back into the next run.
     */
    FAILED
}
