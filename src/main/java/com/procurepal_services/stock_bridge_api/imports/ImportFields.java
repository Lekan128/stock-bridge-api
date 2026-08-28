package com.procurepal_services.stock_bridge_api.imports;

/**
 * The field keys both sides speak - BULK_IMPORT_CONTRACT.md section 5, as constants.
 *
 * <p>They are snake_case and identical to the template column headers on purpose, so a header
 * maps to itself in the common case and {@code column_mapping} is an identity map for our own
 * template. Spelled once here so the handler, the mapper, the copy and the report cannot drift
 * from each other or from {@code ProductExcelService.ALL_HEADER_NAMES} / {@code
 * StockInExcelService.HEADER_NAMES}, which are the same strings on the spreadsheet side.
 */
public final class ImportFields {

    private ImportFields() {
    }

    // PRODUCT_CATALOG - the first ten unchanged and in this order. The stability promise in
    // ProductExcelService's javadoc is real: tenants have saved copies of the template.
    public static final String NAME = "name";
    public static final String SKU = "sku";
    public static final String DESCRIPTION = "description";
    public static final String UNIT_PRICE = "unit_price";
    public static final String COST_PRICE = "cost_price";
    public static final String QUANTITY_ON_HAND = "quantity_on_hand";
    public static final String LOW_STOCK_THRESHOLD = "low_stock_threshold";
    public static final String UNIT_OF_MEASURE = "unit_of_measure";
    public static final String PACKAGING_UNIT = "packaging_unit";
    public static final String PACKAGING_SIZE = "packaging_size";
    public static final String VENDOR_NAME = "vendor_name";
    public static final String VENDOR_SKU = "vendor_sku";
    public static final String IS_PREFERRED_VENDOR = "is_preferred_vendor";

    // STOCK_IN
    public static final String PRODUCT_NAME = "product_name";
    public static final String QUANTITY = "quantity";
    public static final String UNIT = "unit";
    public static final String UNIT_COST = "unit_cost";
    public static final String RECEIVED_DATE = "received_date";
    public static final String REFERENCE = "reference";

    /**
     * Reserved keys inside {@code import_session_rows.normalized}.
     *
     * <h2>Why bookkeeping lives inside a jsonb column</h2>
     * M1 owns the schema and nobody but M1 touches {@code db/migration}, so four facts this
     * module needs per row - which cells the user edited by hand, which parent a continuation
     * row belongs to, what the commit did to the row, and what the row's entity looked like
     * before the commit changed it - have no column of their own. They are namespaced with a
     * leading underscore inside {@code normalized}, which is safe because a field key is a
     * spreadsheet column header normalized by {@code HeaderNames}, and that never produces a
     * leading underscore. Every one of them is stripped when the row is projected onto the wire,
     * so the frontend sees exactly the contract section 4 shape and nothing else.
     *
     * <p>{@link #EDITED} is the one that earns its keep least obviously. Without it, a cell the
     * user deliberately cleared is indistinguishable from a cell they never touched, because
     * both are null in {@code normalized} - and re-validation would helpfully "restore" the
     * file's original value into a cell the user had just emptied on purpose.
     *
     * <p>{@link #BEFORE} is what M1's note about undo was pointing at: {@code
     * products.import_batch_id} is set on CREATE only, so "created by this batch" is a column
     * but "touched by this batch" is not. An update's undo has to consult the row snapshot, and
     * this is the snapshot - taken of the entity immediately before the commit changed it, not
     * of the file, because the file is what it was changed *to*.
     */
    public static final String EDITED = "_edited";

    public static final String CONTINUATION_OF = "_continuation_of";

    public static final String OUTCOME = "_outcome";

    public static final String OUTCOME_MESSAGE = "_outcome_message";

    public static final String BEFORE = "_before";

    /**
     * Set by a handler that has decided this row is nothing to do - currently only a stock-in row
     * with a blank quantity.
     *
     * <p>Contract section 8.11 is unambiguous: a blank {@code quantity} on a stock-in row is a
     * silent skip and NOT an error. The pre-filled template is a download of the tenant's entire
     * catalog (design 8.1), so a six-line delivery arrives as a four-hundred-row file with three
     * hundred and ninety-four deliberately empty rows. Erroring on them would be absurd; leaving
     * them VALID would import three hundred and ninety-four zero-quantity receipts. Neither the
     * user nor the engine can decide this - only the handler knows what a blank primary input
     * means for its kind - so the handler says so here and the engine marks the row SKIPPED.
     */
    public static final String AUTO_SKIP = "_auto_skip";

    /**
     * Set when the user pressed Skip on this row, as distinct from a handler deciding there was
     * nothing to do.
     *
     * <p>The two have to be told apart or a stock-in row that auto-skipped for having no quantity
     * would stay skipped forever: the next validation pass would read SKIPPED off the row's
     * status, conclude the user had chosen it, and never re-examine the quantity the user just
     * typed in. Only this flag survives a pass; {@link #AUTO_SKIP} is re-decided every time.
     */
    public static final String USER_SKIPPED = "_user_skipped";

    public static boolean isReserved(String key) {
        return key != null && key.startsWith("_");
    }

    // Outcome values, contract section 4's ImportRowResponse.outcome.
    public static final String OUTCOME_CREATED = "CREATED";
    public static final String OUTCOME_UPDATED = "UPDATED";
    public static final String OUTCOME_SKIPPED = "SKIPPED";
    public static final String OUTCOME_FAILED = "FAILED";
}
