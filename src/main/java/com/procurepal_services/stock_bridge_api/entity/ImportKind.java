package com.procurepal_services.stock_bridge_api.entity;

/**
 * Which row handler commits an {@link ImportSession}, and nothing else. Stored as its name in
 * {@code import_sessions.kind}, which carries a CHECK on exactly these two spellings -
 * BULK_IMPORT_CONTRACT.md section 1 freezes them, and the frontend mirrors them as a
 * string-literal union.
 *
 * <h2>Two imports, deliberately, and why they must stay separate</h2>
 * These read like one feature with a flag, and BULK_IMPORT_DESIGN.md section 6.7 spends its
 * length arguing they are not: their cadence, their key and their fill pattern are opposites.
 * A catalog import answers "what do we stock?" - identity, run rarely, and <b>the user brings
 * the rows</b>. A stock-in answers "what arrived?" - an event with a date, a vendor, a cost and
 * an invoice behind it, run constantly, and <b>we bring the rows and the user brings one
 * number</b>. Every system the design doc surveyed splits them the same way (Odoo: Products vs
 * Inventory Adjustment; NetSuite: Item vs Inventory Adjustment record types; Zoho: Items vs
 * Inventory Adjustments).
 *
 * <p>What this enum is therefore NOT is a mode switch over one shared spreadsheet. It selects
 * the row handler at the commit step (BULK_IMPORT_CONTRACT.md section 2's {@code
 * ImportRowHandler}) - and only that. Everything before the handler - file handling, column
 * mapping, error surfacing, the review grid, value resolution, the confirmation summary, the
 * result report - is kind-agnostic and built once. Building those twice is named in design doc
 * section 1 as "the main avoidable mistake available here".
 *
 * <h2>Where the two meet</h2>
 * In exactly one place, and it is not a combined template: a delivery containing a product the
 * tenant has never stocked. That resolves through the value resolver's inline-create escape
 * hatch inside the {@link #STOCK_IN} flow (design doc 6.7), not by making the user run a
 * catalog import first and start over.
 */
public enum ImportKind {

    /**
     * Build or update the catalog. Carries {@code quantity_on_hand} as an <b>opening balance</b>
     * - a one-time onboarding fact ("this is what was on the shelf the day we started
     * counting"), never a delivery - and since BULK_IMPORT_DESIGN.md section 3 that opening
     * balance writes a real {@code IN} {@link StockMovement} rather than being typed straight
     * into {@code products.quantity_on_hand}. Available only on a row that CREATES a product:
     * an update row's quantity column is ignored, with the review grid saying so and linking to
     * stock-in, so there is exactly one way to add stock to a product that already exists.
     */
    PRODUCT_CATALOG,

    /**
     * Record deliveries bought outside the platform. Keyed on SKU, pre-filled from the tenant's
     * own catalog, and in the common case the user fills exactly one column - {@code quantity}.
     * A blank quantity is a silent skip, not an error, which is what makes a 400-row pre-filled
     * sheet usable for a 12-row delivery (design doc 5.3).
     *
     * <p>{@link ImportMode} is meaningless here and is persisted as {@link ImportMode#CREATE_ONLY}
     * and ignored, per contract section 1.
     */
    STOCK_IN
}
