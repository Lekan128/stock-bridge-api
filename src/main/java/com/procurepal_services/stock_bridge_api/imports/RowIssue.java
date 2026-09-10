package com.procurepal_services.stock_bridge_api.imports;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One thing wrong with one cell, in the vocabulary BULK_IMPORT_CONTRACT.md section 4 froze for
 * the review grid: a column to outline, a sentence to print beneath it, an optional one-click
 * suggestion, and - filled in later by the engine - how many other rows share the identical
 * complaint.
 *
 * <h2>Why errors and warnings are the same record</h2>
 * They differ in exactly one way: an error blocks the commit and a warning does not. Everything
 * else about them - where they render, how they are worded, whether they offer a bulk fix - is
 * identical, and the review screen draws them with the same component in two colours. Modelling
 * them as one type with a {@link Severity} means the "an update row's ignored quantity must
 * produce a warning and not silence" rule (contract section 8.8) is one enum value away from
 * the error path rather than a separate parallel mechanism that could quietly be left out.
 *
 * <h2>{@code code}, and why it never reaches the browser</h2>
 * {@code message} is prose written for the person reading the screen (design 9.6: never a column
 * name as the subject, never a UUID). The compatibility shim in section 10, however, has to keep
 * answering in the *old* vocabulary - {@code "is required"}, {@code "'BAG' is not a recognized
 * unit of measure"} - because {@code ProductBulkImportExportIntegrationTest} and any client
 * built against {@code POST /api/products/bulk-upload} assert on those exact strings. Carrying a
 * machine-readable {@code code} alongside the prose lets one validation pass serve both readers:
 * the grid prints {@code message}, and {@code LegacyRowErrors} translates {@code code} back into
 * the legacy sentence. It is stripped when the row is projected onto the wire, so the frontend
 * never sees it and can never start depending on it.
 *
 * @param column the field key this attaches to. Null for a whole-row problem with no single
 *     cell to blame.
 * @param message the sentence, already user-facing. Composed server-side on purpose (contract
 *     section 4) so both kinds read alike and the frontend never string-builds copy.
 * @param suggestion the value a single click would apply - the {@code "Did you mean Kilogram
 *     (kg)?"} half of the highest-value interaction on the review screen. Null when there is
 *     nothing to guess.
 * @param code stable identifier for this class of problem, server-side only. Also the key the
 *     bulk-fix count groups on, so two differently-worded messages about the same underlying
 *     mistake still count as one fixable group.
 * @param severity whether this blocks the commit.
 */
public record RowIssue(String column, String message, ImportFieldDescriptor.Option suggestion, String code, Severity severity) {

    public enum Severity {
        ERROR,
        WARNING
    }

    /** Keys of the persisted map form. Kept here so the writer and the reader cannot drift. */
    public static final String KEY_COLUMN = "column";
    public static final String KEY_MESSAGE = "message";
    public static final String KEY_CODE = "code";
    public static final String KEY_SEVERITY = "severity";
    public static final String KEY_SUGGESTION_VALUE = "suggestionValue";
    public static final String KEY_SUGGESTION_LABEL = "suggestionLabel";
    public static final String KEY_BULK_FIX_COUNT = "bulkFixCount";

    public static RowIssue error(String column, String code, String message) {
        return new RowIssue(column, message, null, code, Severity.ERROR);
    }

    public static RowIssue error(
            String column, String code, String message, ImportFieldDescriptor.Option suggestion) {
        return new RowIssue(column, message, suggestion, code, Severity.ERROR);
    }

    public static RowIssue warning(String column, String code, String message) {
        return new RowIssue(column, message, null, code, Severity.WARNING);
    }

    /**
     * A warning that knows the answer - the shape UNIT_UX_CONTRACT.md section 5.1 asks for:
     * "20 kg - did you mean 20 bags (1,000 kg)?" with the corrected value one click away.
     *
     * <p>Most warnings have nothing to suggest and use the overload above. This one exists
     * because the opening-stock-looks-like-packs case is the rare warning where the system knows
     * exactly what the user probably meant but must not apply it unasked: twenty kilograms of
     * rice is a legal statement, just an unusual one. Offering the value is the whole difference
     * between a message that helps and a message that worries.
     */
    public static RowIssue warning(
            String column, String code, String message, ImportFieldDescriptor.Option suggestion) {
        return new RowIssue(column, message, suggestion, code, Severity.WARNING);
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    /**
     * The jsonb form persisted into {@code import_session_rows.errors}.
     *
     * <p>M1 left that column typed {@code List<Map<String, Object>>} on purpose, noting that
     * section 4's {@code suggestion}/{@code bulkFixCount} vocabulary belongs to this module and
     * not to the entity layer. This method is where that vocabulary is actually spelled, and the
     * suggestion is flattened into two scalar keys rather than nested so that a hand-written SQL
     * query against the column stays readable.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(KEY_COLUMN, column);
        map.put(KEY_MESSAGE, message);
        map.put(KEY_CODE, code);
        map.put(KEY_SEVERITY, severity.name());
        if (suggestion != null) {
            map.put(KEY_SUGGESTION_VALUE, suggestion.value());
            map.put(KEY_SUGGESTION_LABEL, suggestion.label());
        }
        return map;
    }
}
