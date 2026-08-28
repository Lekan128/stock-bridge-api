package com.procurepal_services.stock_bridge_api.imports.io;

import java.util.List;
import java.util.Map;

/**
 * The input shapes for {@link ImportResultReportWriter} - what a row handler hands over once a
 * commit has finished.
 *
 * <p>Deliberately not tied to {@code ImportSessionRow}: the writer would then need the entity, the
 * enums and the JSON column shape, and the report would be impossible to build for anything that
 * is not a persisted session. Three plain records instead, filled by whoever has the data.
 */
public final class ImportResultReport {

    private ImportResultReport() {
    }

    /**
     * One column of the report, in the order it should appear.
     *
     * @param key looked up in each {@link Row#values()}.
     * @param label the header text. Usually the human label of the field rather than the field key
     *     - a report is read by an accountant three weeks later, not by the frontend.
     */
    public record Column(String key, String label) {
    }

    /**
     * One row of the original file, with what happened to it.
     *
     * @param excelRow the row number in the file the user uploaded, so a line in this report can be
     *     matched to a line in the spreadsheet on their desk. The single most useful column here.
     * @param outcome {@code CREATED}, {@code UPDATED}, {@code SKIPPED} or {@code FAILED} - the
     *     spellings BULK_IMPORT_CONTRACT.md section 4 gives {@code ImportRowResponse.outcome}.
     *     Free text rather than an enum so this class does not have to depend on M4's vocabulary,
     *     and so a handler with a fifth outcome is not blocked on editing this file.
     * @param message why, for the outcomes that need a why. Blank for the ones that do not - a
     *     "Created successfully" on every one of 400 rows is noise that hides the four that failed.
     * @param values the row's own values, keyed by the report's column keys.
     */
    public record Row(int excelRow, String outcome, String message, Map<String, String> values) {

        public Row {
            values = values == null ? Map.of() : Map.copyOf(values);
        }
    }

    /**
     * Everything the report needs besides its rows.
     *
     * @param headline the sentence at the top of the sheet, composed by the caller in the same
     *     past-tense voice as the result screen ("Imported 42 rows from products-jan.xlsx").
     * @param columns the file's own columns, in template order.
     */
    public record Sheet(String headline, List<Column> columns) {

        public Sheet {
            columns = List.copyOf(columns);
        }
    }
}
