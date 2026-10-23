package com.procurepal_services.stock_bridge_api.stock.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * The losses report (MULTI_BRANCH_UX_PLAN.md section 5.7, MULTI_BRANCH_BUILD_PLAN.md section 6): stock
 * that left the books without being sold or used, at what it cost.
 *
 * <p>Kinds: {@code MISSING_IN_TRANSIT} and {@code DAMAGED_IN_TRANSIT} (waybills; missing stock found
 * again is netted out), {@code COUNTED_SHORT} and {@code COUNTED_OVER} (stock counts; over is a gain,
 * so its cost counts against the loss), {@code DAMAGED}, {@code EXPIRED}, {@code LOST} (stock-outs
 * given those reasons).
 *
 * @param groupBy {@code kind}, {@code branch}, {@code person} (who recorded it; for transit, who
 *     carried it) or {@code month}.
 */
public record LossesResponse(
        OffsetDateTime from, OffsetDateTime to, String groupBy, List<Row> rows, Figures totals) {

    public record Row(String key, String label, Figures figures) {
    }

    /**
     * @param units net units lost (gains from counts taken off).
     * @param cost net cost lost, over the entries whose cost is known.
     * @param costUnknown entries left out of {@code cost} because nothing says what they cost.
     */
    public record Figures(long units, BigDecimal cost, long costUnknown, Map<String, Kind> byKind) {
    }

    public record Kind(long units, BigDecimal cost, long entries) {
    }
}
