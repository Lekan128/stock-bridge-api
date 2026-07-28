package com.procurepal_services.stock_bridge_api.marketplace.catalog.dto;

import java.util.List;
import java.util.UUID;

/**
 * Partial success is the right answer for a bulk action driven by a checkbox column:
 * failing all 40 rows because one id was stale (another admin deleted it, or it was
 * never ProcurePal's) would be worse than listing the 39 and saying so. The skipped
 * ids come back so the UI can keep exactly those rows selected.
 *
 * {@code updated} counts rows whose state actually changed, so re-running the same
 * bulk action reports 0 rather than lying about work it did not do.
 */
public record BulkListingResponse(int updated, int alreadyInState, List<UUID> skipped) {
}
