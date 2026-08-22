package com.procurepal_services.stock_bridge_api.settlement.dto;

import java.math.BigDecimal;

/**
 * The four movement totals for a statement window, grouped the way a vendor reads
 * them rather than the way the ledger stores them.
 *
 * <h2>The reconciliation identity</h2>
 * <pre>
 *   openingBalance + salesProceeds + commission + reversals + payouts = closingBalance
 * </pre>
 * Every term is signed, so that is plain addition with no sign flipping - which is
 * the point. {@link #netMovement} is the sum of the four and is included so a
 * client can check the identity in one subtraction instead of four additions, and
 * so a rendering bug in one column cannot hide inside a correct total.
 *
 * <p>Every one of these is a partition of the same {@code SUM(amount)}: the five
 * ledger kinds map onto four columns, with the two reversal kinds folded together
 * because a vendor thinks of "that returned order" as one event rather than as a
 * credit and a debit. The individual rows are still in the statement's line list
 * if anyone needs to take them apart.
 *
 * @param salesProceeds sum of {@code SALE_PROCEEDS}. Positive. What the buyers paid
 *     for this vendor's goods on orders whose delivery was confirmed in the window.
 *     Does NOT include the delivery fee - that is the platform's logistics charge
 *     and never enters a vendor's ledger.
 * @param commission sum of {@code COMMISSION}. NEGATIVE, because it reduces what the
 *     vendor is owed. This is the "fees accumulated" figure the statement was asked
 *     for; its magnitude is what the platform earned.
 * @param reversals sum of {@code SALE_REVERSAL} and {@code COMMISSION_REVERSAL}
 *     together. Signed, and usually negative - a fully reversed sale gives back the
 *     fee but takes back the larger proceeds. A window containing only a commission
 *     correction would make it positive, which is correct and not a bug.
 * @param payouts sum of {@code PAYOUT}. Negative. Money that actually left the bank,
 *     posted when an operator marked a batch paid - never when it was merely
 *     computed.
 * @param netMovement the four added together. Opening plus this is closing, exactly.
 */
public record VendorStatementMovements(
        BigDecimal salesProceeds,
        BigDecimal commission,
        BigDecimal reversals,
        BigDecimal payouts,
        BigDecimal netMovement) {
}
