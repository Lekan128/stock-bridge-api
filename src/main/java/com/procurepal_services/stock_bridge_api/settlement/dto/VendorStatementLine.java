package com.procurepal_services.stock_bridge_api.settlement.dto;

import com.procurepal_services.stock_bridge_api.entity.VendorLedgerEntryType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One line of a vendor statement: one ledger row, plus just enough of the order it
 * came from that the vendor can find it in their own records.
 *
 * <h2>Why the arithmetic is on the line and not only in the totals</h2>
 * The requirement this module was given is that a vendor must be able to check the
 * statement by hand. So a commission line carries {@link #basisAmount} and
 * {@link #commissionRate} as well as {@link #amount}: the reader can multiply the
 * first two, round half-up to the kobo, and get the third, without being shown the
 * order line or trusting a total. See {@code VendorCommission} for the rule.
 *
 * @param amount signed from the VENDOR's point of view - positive means the
 *     platform owes them more. Sales proceeds and commission reversals are
 *     positive; commission, sale reversals and payouts are negative. Presenting an
 *     unsigned magnitude with a separate direction column was rejected: the column
 *     has to add up to the closing balance, and a reader adding a column of
 *     unsigned numbers gets the wrong answer.
 * @param runningBalance the balance AFTER this line, so opening + every line = closing
 *     is checkable at any row rather than only at the bottom. This is the field that
 *     makes a disputed statement debuggable in one pass.
 * @param orderNumber the human-quotable {@code PP-YYYY-NNNNNN}. Null on a payout,
 *     which settles a fortnight of orders rather than one.
 * @param productName snapshotted on the order line at sale time, so it says what the
 *     invoice said even if the product has since been renamed.
 * @param commissionRate present on commission lines only, as a fraction -
 *     {@code 0.0750} is 7.5%. Null elsewhere, never zero-by-coincidence.
 * @param basisAmount what the rate was applied to. Travels with the rate, always.
 * @param reversesEntryId the id of the line this one corrects, on the two reversal
 *     kinds. What makes a correction auditable rather than merely opposite.
 */
public record VendorStatementLine(
        UUID id,
        OffsetDateTime occurredAt,
        VendorLedgerEntryType type,
        UUID orderId,
        String orderNumber,
        UUID orderItemId,
        String productName,
        Integer quantity,
        BigDecimal basisAmount,
        BigDecimal commissionRate,
        BigDecimal amount,
        BigDecimal runningBalance,
        String memo,
        UUID reversesEntryId) {
}
