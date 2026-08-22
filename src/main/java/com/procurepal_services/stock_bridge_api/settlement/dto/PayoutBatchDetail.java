package com.procurepal_services.stock_bridge_api.settlement.dto;

import java.util.List;

/**
 * A batch with the ledger lines it claimed - the audit view, and the answer to
 * "which lines went into this and what was the total".
 *
 * <p>The lines are {@link VendorStatementLine}s, the same record the statement
 * uses, so an operator investigating a dispute is reading the identical rows the
 * vendor is looking at rather than a parallel operator-only rendering of them. The
 * running balance on those lines is the batch's own cumulative total rather than
 * the vendor's account balance - stated here because that is the one field whose
 * meaning changes with the context it is served in.
 *
 * @param linesTotal the sum of the lines, recomputed on read. It must equal
 *     {@code batch.netAmount()}, which was frozen when the batch was created; the
 *     two are reported separately so a disagreement is visible rather than
 *     impossible to detect. See {@code VendorPayoutBatch} for why the totals are
 *     frozen at all.
 */
public record PayoutBatchDetail(
        PayoutBatchSummary batch, List<VendorStatementLine> lines, java.math.BigDecimal linesTotal) {
}
