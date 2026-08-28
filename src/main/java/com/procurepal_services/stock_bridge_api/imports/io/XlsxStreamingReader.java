package com.procurepal_services.stock_bridge_api.imports.io;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.model.SharedStrings;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Reads the first sheet of an .xlsx file a row at a time, off the SAX event stream, without ever
 * materializing a workbook.
 *
 * <h2>Why not XSSFWorkbook, which the pre-existing parser used</h2>
 * BULK_IMPORT_DESIGN.md section 11 is direct about it: {@code XSSFWorkbook} inflates the whole
 * file into an object graph before the first cell can be read - fine at the couple of hundred
 * rows the original bulk upload was designed for, and not fine at the 5,000-row cap this feature
 * advertises, where the object graph is tens of megabytes for a file of two. Worse, the cost is
 * paid <em>before</em> anything can decide the file is too big, which is the exact ordering
 * section 11 forbids. Streaming inverts that: {@link #countDataRows} answers "how many rows is
 * this" off the sheet's own {@code dimension} element or a bare row count, and the expensive pass
 * only ever runs on a file already known to be within the limits.
 *
 * <p>Generation stays on {@code XSSFWorkbook} (see {@code WorkbookBuilder}) - writing needs random
 * access to set styles, comments, validations and named ranges, and a template is small by
 * construction.
 *
 * <h2>Raw values, not formatted ones</h2>
 * POI ships {@code XSSFSheetXMLHandler}, which would have saved most of this class, and it is
 * deliberately not used: it runs numeric cells through a {@code DataFormatter}, so a cell holding
 * 45000 in a column this module's own template formats as {@code #,##0} comes back as the string
 * {@code "45,000"} - which the parser would then have to un-format, guessing at the user's
 * thousands separator to do it. Reading the raw stored value instead means the number that
 * reaches the parser is the number Excel stored, and the number formats the template applies are
 * purely a display decision, which is what they should be.
 *
 * <p>The one exception is dates, and it has to be: a date cell stores a serial number, so
 * {@code received_date} would arrive as {@code "46000"} with no way to tell it from a quantity.
 * Those cells - and only those - are identified from the styles table and emitted as ISO-8601
 * text, the same shape the CSV reader produces for a date typed as text. The date-system flag is
 * taken as the 1900 default: the 1904 system is Mac Excel 2008-and-earlier only, and reading the
 * workbook part solely to check it would cost more than the four-year error it prevents on files
 * that effectively no longer exist.
 *
 * <h2>Zip safety</h2>
 * {@link OPCPackage} applies POI's {@code ZipSecureFile} inflate-ratio guard on every entry it
 * opens, which is the zip-bomb defence section 11 asks for. It is left at POI's default ratio
 * rather than re-set here: the guard is global static state, and a class that "reaffirms" it is
 * one refactor away from being the class that quietly weakens it.
 */
final class XlsxStreamingReader {

    /** ISO-8601 date, or date-and-time when the cell genuinely carries a time component. */
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private static final DateTimeFormatter ISO_DATE_TIME = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private XlsxStreamingReader() {
    }

    /**
     * How many DATA rows the first sheet has, without interpreting a single cell. Used to reject
     * an over-cap file at upload with the real number in the message.
     *
     * <p>Prefers the sheet's {@code <dimension>} element, which Excel, LibreOffice and POI itself
     * all write and which gives the answer from the first few hundred bytes of the stream. When
     * it is missing or degenerate (some writers emit {@code ref="A1"} regardless of content), it
     * falls back to counting {@code <row>} elements - still far cheaper than a parse, because no
     * shared string is resolved and no value is ever built.
     *
     * <p>Ghost rows count. A file whose fill handle was dragged 300 rows past the data is
     * genuinely 300 rows longer as far as every other tool is concerned, and the alternative -
     * resolving each ghost cell's shared string to discover it holds a zero-width space - is the
     * full parse this method exists to avoid.
     */
    static int countDataRows(byte[] content) {
        try (OPCPackage pkg = OPCPackage.open(new ByteArrayInputStream(content))) {
            org.apache.poi.xssf.eventusermodel.XSSFReader reader =
                    new org.apache.poi.xssf.eventusermodel.XSSFReader(pkg);
            try (InputStream sheet = firstSheet(reader)) {
                RowCountingHandler handler = new RowCountingHandler();
                parse(sheet, handler);
                return Math.max(0, handler.lastRowNumber - 1);
            }
        } catch (SpreadsheetReadException e) {
            throw e;
        } catch (Exception e) {
            throw notASpreadsheet(e);
        }
    }

    /**
     * The full read. {@code maxDataRows} is enforced again here rather than trusted from
     * {@link #countDataRows}: the two are separate passes over a byte array a caller could in
     * principle have swapped between them, and more practically, a sheet whose {@code dimension}
     * lied is caught here instead of being allowed to stream unbounded.
     */
    static SheetTable read(byte[] content, int maxDataRows) {
        try (OPCPackage pkg = OPCPackage.open(new ByteArrayInputStream(content))) {
            org.apache.poi.xssf.eventusermodel.XSSFReader reader =
                    new org.apache.poi.xssf.eventusermodel.XSSFReader(pkg);
            SharedStrings sharedStrings = new ReadOnlySharedStringsTable(pkg);
            StylesTable styles = reader.getStylesTable();
            try (InputStream sheet = firstSheet(reader)) {
                SheetHandler handler = new SheetHandler(sharedStrings, styles, maxDataRows);
                parse(sheet, handler);
                if (handler.headers == null) {
                    throw new SpreadsheetReadException(
                            SpreadsheetReadException.Reason.NO_HEADER_ROW,
                            "This file's first sheet is empty - the first row needs to be the column headings.");
                }
                return SheetTable.of(handler.headers, handler.rows);
            }
        } catch (SpreadsheetReadException e) {
            throw e;
        } catch (Exception e) {
            throw notASpreadsheet(e);
        }
    }

    private static InputStream firstSheet(org.apache.poi.xssf.eventusermodel.XSSFReader reader) throws Exception {
        Iterator<InputStream> sheets = reader.getSheetsData();
        if (!sheets.hasNext()) {
            throw new SpreadsheetReadException(
                    SpreadsheetReadException.Reason.NO_HEADER_ROW, "This file has no sheets in it.");
        }
        // Workbook order, so this is the leftmost tab - the one the user was looking at. The
        // hidden _lookups sheet this module's own templates carry is always created after the
        // data sheet, so it can never be picked up here by accident.
        return sheets.next();
    }

    private static void parse(InputStream sheet, DefaultHandler handler) throws Exception {
        XMLReader xmlReader = XMLHelper.newXMLReader();
        xmlReader.setContentHandler(handler);
        xmlReader.parse(new InputSource(sheet));
    }

    private static SpreadsheetReadException notASpreadsheet(Exception cause) {
        return new SpreadsheetReadException(
                SpreadsheetReadException.Reason.NOT_A_SPREADSHEET,
                "The uploaded file is not a valid .xlsx file",
                cause);
    }

    /**
     * Reads {@code <dimension ref="A1:M42"/>} if it is there and counts {@code <row>} elements
     * otherwise. Both answers land in the same field, so the caller does not have to know which
     * one it got.
     */
    private static final class RowCountingHandler extends DefaultHandler {

        private int lastRowNumber;

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            if ("dimension".equals(qName)) {
                int fromDimension = lastRowOf(attributes.getValue("ref"));
                if (fromDimension > lastRowNumber) {
                    lastRowNumber = fromDimension;
                }
            } else if ("row".equals(qName)) {
                String r = attributes.getValue("r");
                int rowNumber = r == null ? lastRowNumber + 1 : parseIntOrZero(r);
                if (rowNumber > lastRowNumber) {
                    lastRowNumber = rowNumber;
                }
            }
        }

        private int lastRowOf(String ref) {
            if (ref == null) {
                return 0;
            }
            int colon = ref.indexOf(':');
            String end = colon < 0 ? ref : ref.substring(colon + 1);
            StringBuilder digits = new StringBuilder();
            for (int i = 0; i < end.length(); i++) {
                if (Character.isDigit(end.charAt(i))) {
                    digits.append(end.charAt(i));
                }
            }
            return digits.isEmpty() ? 0 : parseIntOrZero(digits.toString());
        }

        private int parseIntOrZero(String value) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    /**
     * The value-producing handler. Emits one {@link SheetRow} per non-blank row, with cells at
     * their true column index - a sheet omits empty cells entirely from the XML, so column
     * position has to be recovered from each cell's {@code r} reference rather than from the
     * order events arrive in.
     */
    private static final class SheetHandler extends DefaultHandler {

        private final SharedStrings sharedStrings;
        private final StylesTable styles;
        private final int maxDataRows;

        private List<String> headers;
        private final List<SheetRow> rows = new ArrayList<>();

        private int currentRowNumber;
        private List<String> currentCells = new ArrayList<>();

        private int currentColumn;
        private String currentCellType;
        private int currentStyleIndex = -1;
        private boolean capturingValue;
        private boolean insideInlineString;
        private final StringBuilder value = new StringBuilder();

        SheetHandler(SharedStrings sharedStrings, StylesTable styles, int maxDataRows) {
            this.sharedStrings = sharedStrings;
            this.styles = styles;
            this.maxDataRows = maxDataRows;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            switch (qName) {
                case "row" -> {
                    String r = attributes.getValue("r");
                    currentRowNumber = r == null ? currentRowNumber + 1 : Integer.parseInt(r);
                    currentCells = new ArrayList<>();
                    if (currentRowNumber - 1 > maxDataRows) {
                        throw new SpreadsheetReadException(
                                SpreadsheetReadException.Reason.TOO_MANY_ROWS,
                                ImportLimits.tooManyRowsMessage(currentRowNumber - 1));
                    }
                }
                case "c" -> {
                    currentColumn = columnIndexOf(attributes.getValue("r"));
                    currentCellType = attributes.getValue("t");
                    String s = attributes.getValue("s");
                    currentStyleIndex = s == null ? -1 : Integer.parseInt(s);
                }
                case "v", "t" -> {
                    // <t> appears both inside <is> (an inline string) and inside the shared
                    // strings part; here only the former can occur, and only within a cell.
                    if ("v".equals(qName) || insideInlineString) {
                        value.setLength(0);
                        capturingValue = true;
                    }
                }
                case "is" -> insideInlineString = true;
                default -> {
                    // Everything else - f, f's attributes, extLst, the sheet chrome - is
                    // irrelevant to a value read and is skipped without comment.
                }
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (capturingValue) {
                value.append(ch, start, length);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            switch (qName) {
                case "v", "t" -> {
                    if (capturingValue) {
                        capturingValue = false;
                        setCell(currentColumn, interpret(value.toString()));
                    }
                }
                case "is" -> insideInlineString = false;
                case "row" -> endRow();
                default -> {
                    // See startElement.
                }
            }
        }

        private void endRow() {
            SheetRow row = new SheetRow(currentRowNumber, currentCells);
            if (headers == null) {
                // The first row that has anything in it is the header row, whatever its row
                // number - a file with a blank row 1 is a file whose headers are on row 2, not a
                // file with no headers.
                if (!row.isBlank()) {
                    headers = List.copyOf(currentCells);
                }
                return;
            }
            if (!row.isBlank()) {
                rows.add(row);
            }
        }

        /**
         * Turns one stored cell value into the text the parser sees. Shared strings are resolved,
         * booleans are spelled TRUE/FALSE (what a user typing into the cell would have written,
         * and what {@code is_preferred_vendor} is documented to accept), error cells become
         * nothing at all - a {@code #N/A} is the absence of a value, and passing the literal
         * "#N/A" through would produce "'#N/A' is not a recognized unit" instead of "this cell is
         * empty" - and everything else is the raw stored text, with date-formatted numbers the
         * single documented exception.
         */
        private String interpret(String raw) {
            if (raw.isEmpty()) {
                return null;
            }
            return CellValues.clean(rawInterpret(raw));
        }

        private String rawInterpret(String raw) {
            if ("s".equals(currentCellType)) {
                try {
                    return sharedStrings.getItemAt(Integer.parseInt(raw)).getString();
                } catch (RuntimeException e) {
                    return null;
                }
            }
            if ("b".equals(currentCellType)) {
                return "1".equals(raw) ? "TRUE" : "FALSE";
            }
            if ("e".equals(currentCellType)) {
                return null;
            }
            if ("inlineStr".equals(currentCellType) || "str".equals(currentCellType)) {
                return raw;
            }
            return isDateFormatted() ? asIsoDateText(raw) : normalizeStoredNumber(raw);
        }

        /**
         * Strips the trailing {@code .0} a whole number can carry in the stored XML.
         *
         * <p>Excel itself writes {@code 40}, but POI writes {@code 40.0} for the same value - so a
         * file this application generated and a file the user's Excel generated would otherwise
         * read back differently, and a SKU or a reference number typed as a number would arrive
         * with a decimal point stuck to it. Normalizing through BigDecimal also makes the xlsx and
         * CSV readers agree exactly, which is the property the whole two-format design rests on.
         * Anything that is not a number is returned untouched - the value is already text as far as
         * this method is concerned.
         */
        private String normalizeStoredNumber(String raw) {
            try {
                return new java.math.BigDecimal(raw).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException e) {
                return raw;
            }
        }

        private boolean isDateFormatted() {
            if (styles == null || currentStyleIndex < 0) {
                return false;
            }
            try {
                XSSFCellStyle style = styles.getStyleAt(currentStyleIndex);
                return style != null && DateUtil.isADateFormat(style.getDataFormat(), style.getDataFormatString());
            } catch (RuntimeException e) {
                return false;
            }
        }

        private String asIsoDateText(String raw) {
            try {
                LocalDateTime dateTime = DateUtil.getLocalDateTime(Double.parseDouble(raw));
                boolean midnight = dateTime.toLocalTime().toSecondOfDay() == 0;
                return midnight ? dateTime.toLocalDate().format(ISO_DATE) : dateTime.format(ISO_DATE_TIME);
            } catch (RuntimeException e) {
                return raw;
            }
        }

        private void setCell(int columnIndex, String cellValue) {
            while (currentCells.size() <= columnIndex) {
                currentCells.add(null);
            }
            currentCells.set(columnIndex, cellValue);
        }

        /** {@code "BC12"} to {@code 54} - the letters are a base-26 column number, 1-based. */
        private int columnIndexOf(String cellReference) {
            if (cellReference == null) {
                return currentCells.size();
            }
            int index = 0;
            for (int i = 0; i < cellReference.length(); i++) {
                char c = cellReference.charAt(i);
                if (c < 'A' || c > 'Z') {
                    break;
                }
                index = index * 26 + (c - 'A' + 1);
            }
            return Math.max(0, index - 1);
        }
    }
}
