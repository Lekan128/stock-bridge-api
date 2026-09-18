package com.procurepal_services.stock_bridge_api.imports.io;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * A block of text somebody pasted, as the same {@link SheetTable} a file would have produced
 * (BULK_IMPORT_CX_PLAN.md task 3.2).
 *
 * <h2>Why there is no new parser here</h2>
 * {@link CsvReader} already sniffs comma, semicolon and tab, and a block copied out of Excel or
 * Google Sheets is tab-separated. So the text is simply handed to it. The only thing this class
 * adds is the decision below - which is the one thing a file never has to worry about.
 *
 * <h2>The header row that usually is not there</h2>
 * Every reader we have treats line 1 as the header, which is right for a downloaded template and
 * wrong for a WhatsApp list, where line 1 is a product. Eating it would delete a row the user can
 * see on their own screen - the worst possible kind of bug, because the total is off by one item
 * and nothing says why.
 *
 * <p>So the first line is tested rather than assumed: if none of its cells look like a column
 * name this import kind knows, it is data, and the columns are named {@code "Column 1..N"}. That
 * lands the user on the mapping step that already exists, with every row intact and their own
 * values in front of them. A pasted block that does carry headers is indistinguishable from an
 * uploaded file from here on.
 */
public final class PastedText {

    /** What a column is called when the paste had no header row. One-based, as a person counts. */
    public static final String COLUMN_PREFIX = "Column ";

    private PastedText() {
    }

    /**
     * @param recognisesHeader answers "is this cell the name of a column this kind understands?" -
     *     supplied by the caller because the alias tables belong to {@code ImportColumnMapper} and
     *     this package is deliberately free of import-domain vocabulary.
     */
    public static SheetTable read(String text, Predicate<String> recognisesHeader) {
        if (text == null || text.isBlank()) {
            throw new SpreadsheetReadException(
                    SpreadsheetReadException.Reason.NO_HEADER_ROW,
                    "There is nothing here to read. Paste the rows and try again.");
        }
        SheetTable asRead = CsvReader.read(text.getBytes(StandardCharsets.UTF_8), ImportLimits.MAX_ROWS);
        if (looksLikeHeaderRow(asRead.headers(), recognisesHeader)) {
            return asRead;
        }

        // Line 1 was data. Put it back, and give the columns placeholder names.
        List<SheetRow> rows = new ArrayList<>();
        rows.add(new SheetRow(1, asRead.headers()));
        for (SheetRow row : asRead.rows()) {
            // Excel row numbers shift by one, because what was the header is now row 1.
            rows.add(new SheetRow(row.excelRow() + 1, row.cells()));
        }
        List<String> headers = new ArrayList<>();
        for (int i = 0; i < asRead.headers().size(); i++) {
            headers.add(COLUMN_PREFIX + (i + 1));
        }
        return SheetTable.of(headers, rows);
    }

    /**
     * True when at least one cell names a column we know. One is enough on purpose: a paste whose
     * only recognisable heading is "Product" is still a headed table, and demanding two would send
     * a perfectly good two-column paste down the wrong path.
     *
     * <p>A cell that parses as a number is never a heading, whatever else it looks like - "50" is
     * a quantity, and some alias tables happily contain short words that a stray code could match.
     */
    private static boolean looksLikeHeaderRow(List<String> cells, Predicate<String> recognisesHeader) {
        boolean anyRecognised = false;
        for (String cell : cells) {
            if (cell == null || cell.isBlank()) {
                continue;
            }
            if (NumberValues.parseDecimal(cell).isPresent()) {
                return false;
            }
            if (recognisesHeader.test(cell)) {
                anyRecognised = true;
            }
        }
        return anyRecognised;
    }
}
