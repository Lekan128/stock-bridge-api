package com.procurepal_services.stock_bridge_api.settlement.dto;

import com.procurepal_services.stock_bridge_api.entity.VendorPayoutBatchStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One payout batch as an operator or a vendor sees it. Served on both surfaces
 * unchanged, deliberately: a payout is one of the few things where the vendor and
 * the platform must be looking at literally the same figures, and two records with
 * "the same" fields is how they drift.
 *
 * <p>Nothing here is private to the operator. The super admin who ran it and the
 * one who marked it paid are exposed only as ids, and no name or username is
 * resolved - a vendor has no business knowing which of ProcurePal's staff pressed
 * the button, and the ids are what an internal audit needs.
 *
 * @param netAmount what gets transferred. Always positive - a batch is an
 *     instruction to move money, and a vendor whose reversals outweigh their sales
 *     gets no batch at all rather than one for a negative amount.
 * @param commissionTotal negative, as the ledger stores it, so the four money fields
 *     add up without sign flipping: proceeds + commission + reversals = net.
 * @param periodEnd the exclusive cutoff. Everything unsettled and older than this
 *     went in - including entries older than {@code periodStart} that an earlier run
 *     missed, which is why the two dates do not bound a fixed set of orders.
 * @param settledAt when a human recorded making the transfer. Null until then, and
 *     the presence of this field is what {@code PAID} means - there is no automated
 *     disbursement in this system and no callback to corroborate it.
 * @param paymentReference the bank's reference for the transfer, typed in by the
 *     operator. The only thing tying this row to a real movement of money.
 */
public record PayoutBatchSummary(
        UUID id,
        String batchNumber,
        UUID sellerClientId,
        String sellerName,
        OffsetDateTime periodStart,
        OffsetDateTime periodEnd,
        VendorPayoutBatchStatus status,
        String currency,
        BigDecimal proceedsTotal,
        BigDecimal commissionTotal,
        BigDecimal reversalTotal,
        BigDecimal netAmount,
        int lineCount,
        OffsetDateTime runAt,
        UUID runBy,
        OffsetDateTime settledAt,
        UUID settledBy,
        String paymentReference,
        String failureReason) {
}
