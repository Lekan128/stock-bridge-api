package com.procurepal_services.stock_bridge_api.stock.dto;

import java.math.BigDecimal;

/**
 * The totals row of the stock in/out report - {@code GET /api/stock/movements/summary}, computed
 * over exactly the filters the report's own rows are computed over.
 *
 * <h2>Why this exists when the dashboard already has AnalyticsSummaryResponse</h2>
 * That record answers "how did the whole tenant do over this period" and takes a date range and
 * nothing else. This one answers "what do the rows I am currently looking at add up to", so it
 * has to honour the report's product, supplier and movement-type filters too - a total that
 * ignored the supplier filter would sit above a filtered table contradicting it.
 *
 * <p>Summed by the database over the whole filtered set rather than by the browser over the
 * current page: a page-2-of-40 footer that totalled twenty rows would be a number nobody asked
 * for, and paging to the end to add up a month is not a report.
 *
 * <h2>The count fields are the answer to "why doesn't this add up"</h2>
 * {@code inValue}/{@code outValue} sum only movements that recorded a price, exactly as
 * {@code StockMovementRepository.sumValue} always has, while {@code inQuantity}/{@code
 * outQuantity} count every movement in range. So a tenant can legitimately see 400 units in
 * against a value that covers 380 of them, and without {@code unpricedInCount}/{@code
 * unpricedOutCount} the only available reading of that gap is "the report is wrong". Publishing
 * the count lets the screen say "12 deliveries have no price recorded" instead.
 *
 * <h2>ADJUSTMENT rows are counted and never valued</h2>
 * {@code adjustmentCount} is reported so a discrepancy between opening and closing stock has a
 * visible cause, but adjustments contribute to neither value nor quantity total: a stock-take
 * correction is not a purchase and not a sale, and folding it into either would misstate both.
 */
public record StockMovementSummaryResponse(
        /** Money spent on priced IN movements in range. Never null - zero when nothing qualifies. */
        BigDecimal inValue,
        /** Money recorded against priced OUT movements in range. Never null. */
        BigDecimal outValue,
        /** Units received, in each product's own stock unit, priced or not. */
        long inQuantity,
        /** Units issued, in each product's own stock unit, priced or not. */
        long outQuantity,
        long inMovementCount,
        long outMovementCount,
        /** IN movements in range with no price on file - the gap between inQuantity and inValue. */
        long unpricedInCount,
        /** OUT movements in range with no price on file. */
        long unpricedOutCount,
        long adjustmentCount) {
}
