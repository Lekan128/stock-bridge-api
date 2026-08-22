package com.procurepal_services.stock_bridge_api.settlement.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One vendor's row in the pre-run preview: what a payout run WOULD create for them
 * at the current cutoff, before anything is written.
 *
 * <h2>Why a preview exists at all</h2>
 * Running a batch is not reversible in any tidy way - a mistaken run has to be
 * failed, which releases its lines but leaves a FAILED batch in the history
 * forever. So the operator gets to see the numbers first. It is also the screen
 * that answers "why is this vendor not in the run", which is otherwise
 * unanswerable from the outside.
 *
 * @param eligible whether a batch will actually be created. False when
 *     {@link #netAmount} is zero or negative, which is not an error: a vendor whose
 *     refunds outweigh their new sales is carrying a debt to the platform, and the
 *     correct handling is to create nothing and let it net off against their next
 *     sales. {@link #ineligibleReason} says which case it is.
 * @param ineligibleReason human-readable, null when eligible. Shown verbatim.
 * @param netAmount proceeds + commission + reversals, signed. Can be negative, which
 *     is exactly why this record exists rather than the run silently skipping rows.
 */
public record EligibleVendorPayout(
        UUID sellerClientId,
        String sellerName,
        String sellerSlug,
        int lineCount,
        BigDecimal proceedsTotal,
        BigDecimal commissionTotal,
        BigDecimal reversalTotal,
        BigDecimal netAmount,
        boolean eligible,
        String ineligibleReason) {
}
