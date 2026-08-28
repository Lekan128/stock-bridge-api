package com.procurepal_services.stock_bridge_api.imports.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * The writing side of the spreadsheet machinery: styles, header rows, per-column comments, number
 * formats, and the plumbing that attaches a dropdown to a column. Kind-agnostic on purpose - the
 * product template, the pre-filled stock-in template and the result report are three different
 * sheets built from the same few moves, and a second copy of "how do you attach a data validation
 * in POI" is a second place for it to be attached slightly wrong.
 *
 * <h2>Why generation is still XSSFWorkbook when reading is not</h2>
 * BULK_IMPORT_DESIGN.md section 11 draws the line here explicitly. Writing a template needs random
 * access - a style set after a cell exists, a comment anchored to a header cell, a validation over
 * a range, a defined name pointing at a sheet created later - and {@code SXSSFWorkbook}'s sliding
 * window forbids touching a row once it has been flushed. Templates and reports are also bounded
 * by construction (a template is three rows; a report is one row per imported row, already capped
 * at {@link ImportLimits#MAX_ROWS}), so the memory argument that forces streaming on the READ path
 * simply does not arise on the write path.
 *
 * <h2>Number formats are a parsing feature, not a cosmetic one</h2>
 * A price column with no format is General, so a user who types {@code 45,000} into it gets the
 * <em>string</em> "45,000" - and the parser then has to decide whether that comma is a thousands
 * separator or a decimal point, in a country where both conventions are in daily use. Formatting
 * the column as a number means Excel itself resolves the ambiguity at typing time, against the
 * user's own locale, and stores 45000. Section 7.2 lists this as a template mechanic worth getting
 * right, and it is: it moves a guess out of the server and into the one place that can make it
 * correctly.
 */
public final class WorkbookBuilder implements AutoCloseable {

    /**
     * The last row a dropdown covers. The whole importable range, so a user pasting 4,000 rows
     * under the header still gets validation on all of them, and not a row further - covering the
     * full million-row sheet bloats the file and makes Excel slow to open it.
     */
    private static final int LAST_VALIDATED_ROW = ImportLimits.MAX_ROWS;

    private final XSSFWorkbook workbook;
    private final Sheet sheet;
    private final CreationHelper creationHelper;
    private final Map<String, CellStyle> stylesByKey = new LinkedHashMap<>();
    private Drawing<?> drawing;

    public WorkbookBuilder(String sheetName) {
        this.workbook = new XSSFWorkbook();
        this.sheet = workbook.createSheet(sheetName);
        this.creationHelper = workbook.getCreationHelper();
    }

    public XSSFWorkbook workbook() {
        return workbook;
    }

    public Sheet sheet() {
        return sheet;
    }

    /**
     * Writes the header row, sets each column's width, and attaches one short comment per column.
     *
     * <h2>One comment per column, replacing the single paragraph on B1</h2>
     * The template this replaces carried every rule - example rows, the unit_price condition, the
     * packaging trio's three cross-field constraints, where to find the codes - in one comment
     * anchored to cell B1. Section 7.2 names that as the mistake it is: a four-rule paragraph in
     * one cell is read once, by the person who wrote it. A one-sentence comment on each column's
     * own header is read because it appears exactly where the question is asked, at the moment it
     * is asked, by a user who is already hovering over the column they do not understand.
     *
     * @param commentsByHeader may omit a header, in which case that column simply has no comment.
     *     A column whose name is self-explanatory does not need one, and a comment marker on every
     *     single header is visual noise that makes the useful ones harder to notice.
     */
    public void writeHeaderRow(
            List<String> headers, Map<String, Integer> widthsByHeader, Map<String, String> commentsByHeader) {
        Row header = sheet.createRow(0);
        for (int i = 0; i < headers.size(); i++) {
            String name = headers.get(i);
            Cell cell = header.createCell(i);
            cell.setCellValue(name);
            cell.setCellStyle(headerStyle());
            Integer width = widthsByHeader.get(name);
            sheet.setColumnWidth(i, (width == null ? 18 : width) * 256);
            String comment = commentsByHeader.get(name);
            if (comment != null) {
                addHeaderComment(cell, comment);
            }
        }
        sheet.createFreezePane(0, 1);
    }

    /** Bold, on a light fill - the header row has to read as chrome rather than as the first record. */
    public CellStyle headerStyle() {
        return stylesByKey.computeIfAbsent("header", key -> {
            Font bold = workbook.createFont();
            bold.setBold(true);
            CellStyle style = workbook.createCellStyle();
            style.setFont(bold);
            style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            return style;
        });
    }

    /** Italic grey - visibly not real data, which is what makes the example rows safe to leave in place. */
    public CellStyle exampleStyle() {
        return stylesByKey.computeIfAbsent("example", key -> {
            Font italicGrey = workbook.createFont();
            italicGrey.setItalic(true);
            italicGrey.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            CellStyle style = workbook.createCellStyle();
            style.setFont(italicGrey);
            return style;
        });
    }

    /**
     * The look of a column the user is not meant to edit - grey fill, dark grey text. Used for
     * {@code sku} and {@code product_name} on the pre-filled stock-in sheet, where those two
     * columns exist to identify the row and are ignored (product_name) or matched (sku) on the way
     * back in. Styling is the only lock offered: sheet protection would also stop the user
     * deleting rows they do not need, which is a thing they legitimately want to do.
     */
    public CellStyle referenceStyle() {
        return stylesByKey.computeIfAbsent("reference", key -> {
            Font grey = workbook.createFont();
            grey.setColor(IndexedColors.GREY_80_PERCENT.getIndex());
            CellStyle style = workbook.createCellStyle();
            style.setFont(grey);
            style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            return style;
        });
    }

    /** Money: thousands separated, two decimals. No currency symbol - the tenant's currency is not the sheet's business. */
    public CellStyle moneyStyle() {
        return numberStyle("money", "#,##0.00");
    }

    /** Whole quantities, thousands separated. */
    public CellStyle quantityStyle() {
        return numberStyle("quantity", "#,##0");
    }

    /** Packaging sizes and other measured amounts, which are legitimately fractional (half a bag, 2.5 drums). */
    public CellStyle decimalStyle() {
        return numberStyle("decimal", "#,##0.###");
    }

    /**
     * ISO date format, which is also what the reader emits for a date-formatted cell - so a
     * received_date the user never touches round-trips through the file unchanged, and one they
     * do touch is stored as a real date rather than as text.
     */
    public CellStyle dateStyle() {
        return numberStyle("date", "yyyy-mm-dd");
    }

    /**
     * The one column the user is meant to fill, given a pale highlight so it is findable at a
     * glance on a sheet with four hundred rows and nine columns. This is the visual half of
     * BULK_IMPORT_DESIGN.md section 5.3's "in the common case the user fills exactly one column":
     * the pre-fill does the work, and the highlight is what tells them the work is done and where
     * the remaining gap is. Carries the quantity number format too, since a style is all-or-
     * nothing as a column default and a highlighted column that lost its number format would have
     * traded one feature for another.
     */
    public CellStyle primaryInputStyle() {
        return stylesByKey.computeIfAbsent("primaryInput", key -> {
            CellStyle style = workbook.createCellStyle();
            style.setDataFormat(creationHelper.createDataFormat().getFormat("#,##0"));
            style.setFillForegroundColor(IndexedColors.LEMON_CHIFFON.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            return style;
        });
    }

    /** Centred, for the TRUE/blank flag column, where a left-aligned word looks like a typo. */
    public CellStyle centeredStyle() {
        return stylesByKey.computeIfAbsent("centered", key -> {
            CellStyle style = workbook.createCellStyle();
            style.setAlignment(HorizontalAlignment.CENTER);
            return style;
        });
    }

    private CellStyle numberStyle(String key, String format) {
        return stylesByKey.computeIfAbsent(key, ignored -> {
            CellStyle style = workbook.createCellStyle();
            style.setDataFormat(creationHelper.createDataFormat().getFormat(format));
            return style;
        });
    }

    /**
     * Makes a column's DEFAULT style the given one, so a value typed into any empty cell of that
     * column picks it up. Deliberately the column default rather than a style stamped on each
     * pre-created cell: the rows that matter most are the ones the user adds themselves, below
     * anything this generator wrote.
     */
    public void formatColumn(int columnIndex, CellStyle style) {
        sheet.setDefaultColumnStyle(columnIndex, style);
    }

    /**
     * Attaches a dropdown to one whole column, sourced from a defined name on the hidden lookup
     * sheet.
     *
     * <h2>Three POI details that are each a silent failure if missed</h2>
     * <ul>
     *   <li>{@code createFormulaListConstraint}, not {@code createExplicitListConstraint} - see
     *       {@link LookupSheetWriter} for the 255-character cap that makes the explicit form
     *       unusable here.</li>
     *   <li>{@code setSuppressDropDownArrow(true)} - the XSSF implementation writes this
     *       inverted relative to how it reads, and leaving it at the default produces a
     *       validation that IS enforced but shows no arrow. A dropdown nobody can see is not a
     *       dropdown; it is a cell that mysteriously rejects what you type.</li>
     *   <li>{@code ErrorStyle.WARNING}, not the default STOP - the list is an affordance, not the
     *       gate (section 5.2 caveat 2: server-side validation is the only real one). A hard STOP
     *       would block a user pasting a column of values that are perfectly valid but happen to
     *       be spelled differently, and this module's own parser accepts most of those spellings
     *       anyway. A warning they can dismiss is the honest representation of "we think this is
     *       wrong, and we might be the ones who are wrong".</li>
     * </ul>
     *
     * @param definedName the name returned by {@link LookupSheetWriter#addList}; null is accepted
     *     and does nothing, so a caller with an empty list (a tenant with no vendors) does not
     *     have to branch.
     */
    public void addDropdown(int columnIndex, String definedName, String errorTitle, String errorText) {
        if (definedName == null) {
            return;
        }
        DataValidationHelper helper = sheet.getDataValidationHelper();
        DataValidationConstraint constraint = helper.createFormulaListConstraint(definedName);
        CellRangeAddressList range = new CellRangeAddressList(1, LAST_VALIDATED_ROW, columnIndex, columnIndex);
        DataValidation validation = helper.createValidation(constraint, range);
        validation.setSuppressDropDownArrow(true);
        validation.setShowErrorBox(true);
        validation.setErrorStyle(DataValidation.ErrorStyle.WARNING);
        validation.createErrorBox(errorTitle, errorText);
        sheet.addValidationData(validation);
    }

    /**
     * A comment anchored to one header cell, sized to the text. Comments share a single drawing
     * patriarch per sheet - creating a second one silently discards the first, which is the kind
     * of bug that shows up as "only the last comment appears".
     */
    public void addHeaderComment(Cell headerCell, String text) {
        if (drawing == null) {
            drawing = sheet.createDrawingPatriarch();
        }
        ClientAnchor anchor = creationHelper.createClientAnchor();
        anchor.setAnchorType(ClientAnchor.AnchorType.MOVE_DONT_RESIZE);
        anchor.setCol1(headerCell.getColumnIndex());
        anchor.setCol2(headerCell.getColumnIndex() + 4);
        anchor.setRow1(0);
        // Roughly one line per forty characters, plus one, so a long comment is not clipped and a
        // short one does not leave a big empty box.
        anchor.setRow2(2 + text.length() / 40);
        Comment comment = drawing.createCellComment(anchor);
        comment.setString(creationHelper.createRichTextString(text));
        comment.setAuthor("Procure Paddy");
        headerCell.setCellComment(comment);
    }

    public byte[] toBytes() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write spreadsheet", e);
        }
    }

    @Override
    public void close() {
        try {
            workbook.close();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to close workbook", e);
        }
    }
}
