package com.procurepal_services.stock_bridge_api.settlement.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A vendor's account statement for a date range: fees accumulated, what has been
 * paid, and what will be. The surface the stakeholder asked for by name.
 *
 * <h2>It has to reconcile, and this is the shape that makes it check-able</h2>
 * <pre>
 *   openingBalance + movements.netMovement = closingBalance
 * </pre>
 * exactly, in every window, with no tolerance and no rounding slack - every term is
 * a sum of the same {@code NUMERIC(14,2)} column, and the only computed figure in
 * the whole module (commission) was rounded once, when it was posted, and is copied
 * from then on. Each line additionally carries a running balance, so a vendor who
 * disagrees with the closing figure can find the row where they started disagreeing
 * rather than re-adding the page.
 *
 * <h2>How this relates to "My Sales", and why the two do not contradict each other</h2>
 * The vendor sales analytics screen reports GROSS revenue - goods plus delivery
 * fee, on orders dated in the window, before commission - and its DTO says so. This
 * statement reports the vendor's ACCOUNT, and the two will normally show different
 * numbers for the same month. That is correct, and the three reasons are worth
 * having in one place:
 * <ul>
 *   <li><b>Timing.</b> Analytics dates a sale when the ORDER was placed. The ledger
 *       dates it when delivery was CONFIRMED. A December order received in January
 *       is December revenue and a January accrual.</li>
 *   <li><b>Delivery fee.</b> Analytics includes it; the ledger never does. It is the
 *       platform's logistics charge, not part of what the vendor sold.</li>
 *   <li><b>Commission.</b> Analytics is gross of it by design; this statement is the
 *       place it is deducted.</li>
 * </ul>
 * {@link #salesProceedsBasis} restates the second and third in the payload itself,
 * so a screen showing both numbers can explain the gap without hard-coding this
 * paragraph.
 *
 * <h2>ProcurePal</h2>
 * The platform owner is a seller and reaches this endpoint like any other, and gets
 * a legitimately empty statement with {@link #ledgerBearing} false. ProcurePal
 * paying itself commission would be a debt to itself - a number that means nothing
 * and that every total would then have to exclude. Refusing the request instead was
 * rejected for the reason {@code VendorGuard} gives at length: a 403 on a selling
 * surface locks the platform owner out of its own marketplace, and an empty,
 * explained statement is a better answer than an error.
 *
 * @param ledgerBearing false for ProcurePal, true for a vendor. When false every
 *     figure below is zero and the line list is empty, and a client should say why
 *     rather than render an empty table.
 * @param openingBalance what was owed immediately BEFORE {@code from}. Windows are
 *     half-open, {@code [from, to)}, matching every other dated surface in this
 *     application - so printing two adjacent statements counts every row exactly
 *     once.
 * @param closingBalance what is owed at {@code to}. Equals the escrow position's
 *     {@code heldBalance} when {@code to} is now.
 * @param salesProceedsBasis a fixed human-readable note describing what proceeds do
 *     and do not include, carried in the payload so the statement and its export say
 *     the same thing without the frontend re-deriving it.
 * @param payouts batches whose cutoff falls in the window, newest first - "what you
 *     were paid, and what is on its way". Includes PENDING and FAILED ones, because
 *     a vendor asking "where is my money" is usually asking about exactly those.
 */
public record VendorStatementResponse(
        UUID sellerClientId,
        String sellerName,
        boolean ledgerBearing,
        String currency,
        OffsetDateTime from,
        OffsetDateTime to,
        OffsetDateTime generatedAt,
        BigDecimal openingBalance,
        VendorStatementMovements movements,
        BigDecimal closingBalance,
        VendorEscrowPosition escrow,
        String salesProceedsBasis,
        List<VendorStatementLine> lines,
        List<PayoutBatchSummary> payouts) {
}
