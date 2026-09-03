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

    // PRODUCT_CATALOG. UNIT_UX_CONTRACT.md section 9.4 re-spells four of these keys; every old
    // spelling stays an accepted header read alias in ImportColumnMapper and
    // ProductExcelService.HEADER_ALIASES, so a downloaded sheet still maps. Column ORDER also
    // changed with section 9.1 - see ProductExcelService.ALL_HEADER_NAMES for why the counting
    // columns now come before the quantity columns.
    public static final String NAME = "name";
    public static final String SKU = "sku";
    public static final String DESCRIPTION = "description";
    public static final String UNIT_PRICE = "unit_price";
    public static final String COST_PRICE = "cost_price";

    /**
     * Renamed from {@code quantity_on_hand} by UNIT_UX_CONTRACT.md section 5.1. Section 9.1 kept
     * the spelling and <b>changed what the number means</b>.
     *
     * <h2>It counts packs whenever the row declares one</h2>
     * A row saying stock unit Milliliter, pack Keg, units per pack 50, opening stock 30 is
     * thirty KEGS - 1,500 ml - not thirty millilitres. A row with no pack is stock units,
     * exactly as before. No column states which, because the row already does: the pack columns
     * sitting beside the number ARE the statement.
     *
     * <p>That is the model a real user described in their own words ("30 bags of 80 kg rice =
     * 2,400 kg... when stocking out they will stock out kg by default"), and it is NetSuite's
     * purchase-unit / sale-unit split arrived at independently. Every other entry surface in
     * this system already defaults to the pack; the catalog sheet was the last one still making
     * somebody think in kilograms.
     *
     * <p><b>Decimals are accepted.</b> Thirty kegs and a half-full one is a real shelf and an
     * integer count of packs cannot say it. The converted figure is rounded HALF_UP at scale 0,
     * the same rounding section 3.1 applies to every other quantity, and a conversion that
     * rounds to zero is refused rather than silently stored as nothing.
     *
     * <p>Old spelling {@code quantity_on_hand} is still read - and read under section 9.1's rule
     * like any other row, because nothing has reached production and there is therefore no saved
     * sheet whose bare number needs its old meaning preserved. That was the only argument for
     * the deleted {@code opening_stock_counted_in} column, and it does not hold.
     */
    public static final String OPENING_STOCK = "opening_stock";

    /**
     * Renamed from {@code low_stock_threshold} - UNIT_UX_CONTRACT.md section 9.4. Counted the
     * same way {@link #OPENING_STOCK} is (section 9.1): packs when the row declares one, stock
     * units when it does not.
     *
     * <p>Two quantity columns on one row counting different things is the defect section 9
     * exists to remove, so this one is not allowed to diverge from the number above it. The name
     * also stops describing a database column and starts saying what the user is asking for -
     * "tell me when stock falls to".
     */
    public static final String LOW_STOCK_ALERT_AT = "low_stock_alert_at";

    /**
     * Renamed from {@code unit_of_measure} - UNIT_UX_CONTRACT.md section 9.4, matching section
     * 1's locked vocabulary, which bans "unit of measure" and "UoM" as user-facing spellings.
     * The header, the field key and the on-screen label are now the same idea spelled once.
     */
    public static final String STOCK_UNIT = "stock_unit";

    /**
     * Renamed from {@code packaging_unit} - UNIT_UX_CONTRACT.md section 9.4. Section 1's locked
     * name for the container a product is bought and sold in.
     */
    public static final String PACK = "pack";

    /**
     * Renamed from {@code packaging_size} - UNIT_UX_CONTRACT.md section 9.4. Section 1: when a
     * number must be entered for {@code packagingSize} alone, it is called "Units per pack" -
     * never "pack size", which never says a size OF what.
     */
    public static final String UNITS_PER_PACK = "units_per_pack";

    /**
     * The identifier {@code StockInRowHandler} reads this key by, kept pointing at the same
     * string rather than renamed.
     *
     * <p>The stock-in sheet does not have this column - contract section 5.2 removed it - but it
     * still MAPS the header so that a saved sheet carrying one gets the "we now take the pack
     * from your product setup" warning instead of being reported as a column we did not
     * understand. That handler is another module's file, and section 9.4 is explicit that this
     * is a vocabulary change rather than a migration: "entity fields, DB columns and Java
     * identifiers are not renamed". So the KEY moves and the identifier does not.
     *
     * <p>New code should say {@link #UNITS_PER_PACK}. This is the same string, not a second one.
     */
    public static final String PACKAGING_SIZE = UNITS_PER_PACK;

    public static final String VENDOR_NAME = "vendor_name";
    public static final String VENDOR_SKU = "vendor_sku";
    public static final String IS_PREFERRED_VENDOR = "is_preferred_vendor";

    // STOCK_IN
    public static final String PRODUCT_NAME = "product_name";
    public static final String QUANTITY = "quantity";
    /**
     * The stock-in sheet's reference column - UNIT_UX_CONTRACT.md section 5.2, new here. Read
     * only, never parsed for a value: the server recomputes it from the row's resolved product so
     * the grid states the same answer the sheet did, even after the user has edited the file.
     *
     * <p>It exists as a field key at all so that {@link ImportColumnMapper} recognises the header
     * instead of reporting it as a column we did not understand - which is what a reference
     * column being "unmapped" would say to a user, and it is not true.
     */
    public static final String HOW_YOU_COUNT_IT = "how_you_count_it";

    /**
     * Renamed from {@code unit} - UNIT_UX_CONTRACT.md sections 1 and 5.2. "Counted in" is the
     * locked name for "which unit the number beside it is expressed in"; {@code unit} was the
     * word that meant two different product attributes on alternate rows (P3-2). {@code unit}
     * stays a permanent read alias.
     *
     * <p>This is also the key contract section 6.2 names on the wire:
     * {@code fieldOptions: { "counted_in": [...] }}. The per-row options are looked up by field
     * key, so the key and the column header have to be the same string here or the grid's
     * fallback would silently take over.
     */
    public static final String COUNTED_IN = "counted_in";

    /**
     * Renamed from {@code unit_cost} - UNIT_UX_CONTRACT.md section 5.2. The money on this column
     * is per ONE of whatever {@link #COUNTED_IN} says, and the rename is half of saying so;
     * the descriptor's label and help text are the other half (non-negotiable 2).
     * {@code unit_cost} stays a permanent read alias.
     */
    public static final String COST_PER_UNIT = "cost_per_unit";
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
     * The row's own unit set, already narrowed to its resolved product - contract section 6.2's
     * {@code fieldOptions}, computed during validation and carried here rather than recomputed
     * when the row is projected onto the wire.
     *
     * <p>Held on the row for two reasons. A page of fifty rows would otherwise cost fifty product
     * loads plus fifty supplier-line loads on every scroll, and - more importantly - the options
     * the grid offers must be the ones validation just judged the cell against. Deriving them a
     * second time, from a different place, is exactly the two-answers-to-one-question shape this
     * whole remediation exists to remove.
     *
     * <p>Shape: {@code {"counted_in": [{"value": "BAG", "label": "Bag of 50 kg"}, ...]}}.
     */
    public static final String FIELD_OPTIONS = "_field_options";

    /**
     * The server-composed {@code "= 2,000 kg"} that renders under the quantity cell - contract
     * section 6.2, and non-negotiable 3 ("what the user typed and what the ledger records appear
     * together") applied to the review grid.
     *
     * <p>Null when there is nothing to convert: a row counted in its product's own stock unit
     * would render "= 20 kg" beneath a cell reading 20, which is noise rather than reassurance.
     */
    public static final String BASE_QUANTITY_TEXT = "_base_quantity_text";

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
