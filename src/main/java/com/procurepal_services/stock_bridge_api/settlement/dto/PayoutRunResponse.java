package com.procurepal_services.stock_bridge_api.settlement.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The outcome of a payout run: the batches it created, and what it deliberately did
 * not.
 *
 * <p>{@link #skipped} is not an error count. A vendor is skipped when their net is
 * zero or negative (nothing to pay, or a clawback), or when a live batch already
 * exists for this cutoff - which is what a second click produces, and reporting it
 * as "0 created, 6 skipped" is the honest answer rather than a 409 that leaves the
 * operator unsure whether the first click worked.
 *
 * @param batchesCreated how many vendors got a batch.
 * @param totalNet the sum of those batches' net amounts - what the operator now owes
 *     the bank.
 */
public record PayoutRunResponse(
        OffsetDateTime cutoff,
        OffsetDateTime periodStart,
        int batchesCreated,
        int skipped,
        BigDecimal totalNet,
        List<PayoutBatchSummary> batches) {
}
