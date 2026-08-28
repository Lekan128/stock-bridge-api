package com.procurepal_services.stock_bridge_api.imports.io;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One row of a spreadsheet, whatever the file it came out of. Every cell is a already-cleaned
 * String or null; nothing here knows what a product or a delivery is.
 *
 * <h2>Why strings, and why 1-based</h2>
 * A row arrives as text because that is what the user typed, and because the two source formats
 * have to produce <em>identical</em> shapes for the same content or the CSV path would quietly
 * validate differently from the xlsx path (see {@link SpreadsheetReader}). Typed interpretation -
 * "is this a non-negative integer", "is this a unit code" - belongs to the per-kind parser, which
 * owns the error message that goes with a failure. A reader that returned {@code Double} would be
 * making that decision without being able to explain it.
 *
 * <p>{@code excelRow} is the number the user sees in their spreadsheet's row gutter: header = 1,
 * first data row = 2. It is carried explicitly rather than inferred from list position precisely
 * because the reader drops entirely-blank rows - so position in the list and position in the file
 * genuinely differ, and every error message this feature produces has to quote the latter.
 */
public record SheetRow(int excelRow, List<String> cells) {

    public SheetRow {
        // Collections.unmodifiableList over a copy, not List.copyOf: a row legitimately contains
        // nulls (an empty cell in the middle of a row is the commonest thing in any spreadsheet)
        // and List.copyOf rejects them outright.
        cells = Collections.unmodifiableList(new ArrayList<>(cells));
    }

    /**
     * The cell at a column index, or null when the row is short - which is normal, not
     * exceptional: a spreadsheet row simply ends after its last non-empty cell, and a CSV row may
     * legitimately have fewer fields than the header.
     */
    public String cell(int columnIndex) {
        if (columnIndex < 0 || columnIndex >= cells.size()) {
            return null;
        }
        return cells.get(columnIndex);
    }

    /** True when every cell is absent or blank - the ghost rows a dragged fill handle leaves behind. */
    public boolean isBlank() {
        return cells.stream().allMatch(cell -> cell == null || cell.isBlank());
    }
}
