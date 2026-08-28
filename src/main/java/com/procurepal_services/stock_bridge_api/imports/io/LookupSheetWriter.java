package com.procurepal_services.stock_bridge_api.imports.io;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Name;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * The hidden {@code _lookups} sheet, and the named ranges that make a dropdown of any size
 * possible.
 *
 * <h2>Why not an inline list, which is one line of code</h2>
 * {@code DataValidationHelper.createExplicitListConstraint} embeds the options directly in the
 * cell's validation record, and Excel caps that record at <b>255 characters total</b> -
 * BULK_IMPORT_DESIGN.md section 5.2, caveat 1. The BASE unit codes happen to fit. The 19
 * PACKAGING codes do not, and a tenant's vendor names are not even close: twenty Nigerian company
 * names average well over 255 characters between them, and the cap is reached silently - Excel
 * either truncates the list or refuses to open the file, with no error from POI at write time.
 * The dropdown that fails is therefore the one on the column where a dropdown was most valuable,
 * on the tenants with the most data, and nobody finds out until a customer opens the file.
 *
 * <p>A hidden sheet holding the values, referenced by a defined name in a formula constraint, has
 * no length limit at all and is the workaround every spreadsheet-generating tool converges on.
 *
 * <h2>Hidden, not deleted</h2>
 * {@code setSheetHidden} rather than removing the values after the names are wired: a defined name
 * pointing at a sheet that is not there resolves to {@code #REF!}, and Excel then shows an empty
 * dropdown - the worst outcome, because the column still <em>looks</em> like it has one. Hidden
 * costs a few kilobytes and keeps the safety net in place; a user who unhides the sheet finds a
 * plainly-labelled list of valid values, which is a reasonable thing for a curious user to find.
 *
 * <h2>Layout</h2>
 * One list per column, its own header cell in row 1 naming it, values beneath. Columns rather than
 * rows so that a list can grow without any other list's range moving, and so a human who unhides
 * the sheet reads it the way they read every other sheet.
 */
public final class LookupSheetWriter {

    /**
     * Leading underscore is the widespread convention for "generated, not yours" and sorts a
     * visible copy of the sheet away from the data tab. It is a legal Excel sheet name, but it
     * must be quoted inside a formula, which {@link #refersTo} does.
     */
    public static final String SHEET_NAME = "_lookups";

    private final XSSFWorkbook workbook;
    private final Sheet sheet;
    private final List<String> definedNames = new ArrayList<>();
    private int nextColumn;

    public LookupSheetWriter(XSSFWorkbook workbook) {
        this.workbook = workbook;
        this.sheet = workbook.createSheet(SHEET_NAME);
    }

    /**
     * Writes one list and defines a name over it.
     *
     * @param name the defined name a formula constraint will reference. Must be a legal Excel
     *     name (letters, digits, underscore; not a cell reference), which every caller in this
     *     module satisfies by using lowercase snake_case words.
     * @param values the options, in the order they should appear in the dropdown. Blank and
     *     duplicate values are dropped - a dropdown with an empty entry in it looks broken, and a
     *     duplicated vendor name makes a user wonder which of the two is the right one.
     * @return the defined name, or null when there was nothing to write. Null is the signal to the
     *     caller to leave the column as free text: a formula constraint over an empty range shows
     *     an empty dropdown, which is strictly worse than no dropdown, because the user cannot
     *     tell it apart from a list that failed to load.
     */
    public String addList(String name, List<String> values) {
        List<String> cleaned = values.stream()
                .map(CellValues::clean)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (cleaned.isEmpty()) {
            return null;
        }

        int column = nextColumn++;
        writeCell(0, column, name);
        for (int i = 0; i < cleaned.size(); i++) {
            writeCell(i + 1, column, cleaned.get(i));
        }

        Name definedName = workbook.createName();
        definedName.setNameName(name);
        definedName.setRefersToFormula(refersTo(column, cleaned.size()));
        definedNames.add(name);
        return name;
    }

    /**
     * Hides the sheet. Called once, after every list is written - hiding first and writing after
     * works too, but doing it last makes it obvious at the call site that nothing more is going
     * onto this sheet.
     */
    public void hide() {
        workbook.setSheetHidden(workbook.getSheetIndex(sheet), true);
    }

    /** The names defined so far, for tests and for a caller that wants to assert what it built. */
    public List<String> definedNames() {
        return List.copyOf(definedNames);
    }

    /**
     * {@code '_lookups'!$B$2:$B$20} - absolute on both axes so the range cannot drift if a user
     * inserts rows or columns on the data sheet, and quoted because the sheet name starts with an
     * underscore.
     */
    private String refersTo(int column, int valueCount) {
        String columnLetter = columnLetter(column);
        return "'%s'!$%s$2:$%s$%d".formatted(SHEET_NAME, columnLetter, columnLetter, valueCount + 1);
    }

    private String columnLetter(int columnIndex) {
        StringBuilder letters = new StringBuilder();
        int remaining = columnIndex;
        do {
            letters.insert(0, (char) ('A' + remaining % 26));
            remaining = remaining / 26 - 1;
        } while (remaining >= 0);
        return letters.toString().toUpperCase(Locale.ROOT);
    }

    private void writeCell(int rowIndex, int columnIndex, String value) {
        Row row = sheet.getRow(rowIndex);
        if (row == null) {
            row = sheet.createRow(rowIndex);
        }
        Cell cell = row.createCell(columnIndex);
        cell.setCellValue(value);
    }
}
