package com.procurepal_services.stock_bridge_api.entity;

/**
 * Where one {@link ImportSessionRow} stands, stored as its name in {@code
 * import_session_rows.status} under a CHECK on exactly these five spellings
 * (BULK_IMPORT_CONTRACT.md section 1). Also the {@code status} filter on {@code GET
 * /api/imports/{id}/rows}, which is what lets the review grid default to showing only the rows
 * that need something - nobody scrolls 300 rows looking for red (BULK_IMPORT_DESIGN.md 9.3).
 *
 * <p>The five values are also the five counters cached on the session ({@code
 * import_sessions.valid_count} and its siblings), so the review header renders without
 * scanning rows.
 */
public enum ImportRowStatus {

    /** Nothing wrong with it. Will be written by the commit. */
    VALID,

    /** Something is wrong that the user must fix or skip. Blocks the commit while it remains. */
    ERROR,

    /**
     * Will be committed as-is, but something about it is worth saying first - most commonly
     * "quantity is ignored when updating an existing product" (design doc 6.3/6.7, and
     * contract section 8 non-negotiable 8: that one is never silent).
     */
    WARNING,

    /**
     * Excluded from the commit by decision rather than by failure - either the user marked it
     * skipped in the review grid, or it is a stock-in row with a blank quantity, which is a
     * <b>silent</b> skip and not an error (contract section 8 non-negotiable 11). That silence
     * is what makes a 400-row pre-filled sheet usable for a 12-row delivery.
     */
    SKIPPED,

    /** Written. Set by the commit, alongside the row's outcome in {@code normalized}. Terminal. */
    COMMITTED
}
