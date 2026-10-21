package com.procurepal_services.stock_bridge_api.selling.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The Sales report (SELLING_PRICE_PLAN.md sections 4.7, 6.11). {@code totals} is computed over the
 * very same lines as {@code rows}, so the footer equals the rows - unless {@code truncated}, when
 * there were more rows than were sent and the footer still covers all of them.
 */
public record SalesReportResponse(
        OffsetDateTime from,
        OffsetDateTime to,
        String groupBy,
        List<SalesReportRow> rows,
        boolean truncated,
        SalesTotals totals) {
}
