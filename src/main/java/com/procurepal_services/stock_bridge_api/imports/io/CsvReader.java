package com.procurepal_services.stock_bridge_api.imports.io;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A hand-written RFC 4180 reader, producing exactly the {@link SheetTable} the xlsx reader
 * produces.
 *
 * <h2>Why CSV at all</h2>
 * BULK_IMPORT_DESIGN.md section 11: POI cannot read CSV, and a large share of the ERPs and
 * accounting packages a business is migrating off export nothing else. A user who exports from
 * their old system, uploads it, and is told "the uploaded file is not a valid .xlsx file" has
 * been handed a dead end for a file that is perfectly good data - and the workaround (open it in
 * Excel, Save As xlsx) is exactly the kind of thing that makes software feel like it is refusing
 * to help. It is a support category that disappears for about two hundred lines.
 *
 * <h2>Why hand-written rather than a dependency</h2>
 * The project has no CSV library on its classpath today. Commons CSV or opencsv would each be a
 * new third-party dependency, its own CVE surface and its own upgrade cadence, bought to solve a
 * problem whose entire specification (RFC 4180) is one page long and whose hard parts - quoted
 * fields containing the delimiter, quoted fields containing newlines, doubled quotes as an escape
 * - are the state machine below. The tests cover each of those cases directly. If this ever needs
 * to grow into full dialect detection or streaming megabyte-scale files, swapping in a library is
 * a one-class change because nothing outside this file knows CSV exists.
 *
 * <h2>Two forgiving behaviours worth naming</h2>
 * <ul>
 *   <li><b>Delimiter sniffing.</b> Excel writes semicolon-separated files in any locale whose
 *       decimal separator is a comma, and "export to CSV" from a European-configured system is
 *       therefore routinely not comma-separated at all. The header line is sampled for comma,
 *       semicolon and tab and the most frequent wins, which costs one pass over one line and
 *       turns an unreadable file into a readable one.</li>
 *   <li><b>Encoding fallback.</b> The file is decoded as UTF-8 first. If that produces
 *       replacement characters - the signature of a Windows-1252 file, which is what Excel on
 *       Windows still writes for "CSV (Comma delimited)" - it is re-decoded as Windows-1252
 *       instead, so a vendor named "Adé Foods" survives the trip rather than arriving as
 *       "Ad? Foods" and failing to match the directory entry it should have matched.</li>
 * </ul>
 */
final class CsvReader {

    private static final char[] CANDIDATE_DELIMITERS = {',', ';', '\t'};

    private static final char REPLACEMENT_CHARACTER = '�';

    private CsvReader() {
    }

    /**
     * Counts data rows without building any of them, so an over-cap CSV is rejected on the same
     * terms an over-cap xlsx is. Runs the same quote-aware state machine as the full read - a
     * naive newline count would be wrong for any file with an address or a multi-line note in it,
     * and being wrong here means rejecting a file that was within the limit.
     */
    static int countDataRows(byte[] content) {
        String text = decode(content);
        int rows = 0;
        boolean insideQuotes = false;
        boolean rowHasContent = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (insideQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        i++;
                    } else {
                        insideQuotes = false;
                    }
                }
                continue;
            }
            if (c == '"') {
                insideQuotes = true;
                rowHasContent = true;
            } else if (c == '\n' || c == '\r') {
                if (rowHasContent) {
                    rows++;
                }
                rowHasContent = false;
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
            } else if (!Character.isWhitespace(c)) {
                rowHasContent = true;
            }
        }
        if (rowHasContent) {
            rows++;
        }
        // The header is one of the rows counted above and is not data.
        return Math.max(0, rows - 1);
    }

    static SheetTable read(byte[] content, int maxDataRows) {
        String text = decode(content);
        char delimiter = sniffDelimiter(text);
        List<List<String>> records = split(text, delimiter);

        List<String> headers = null;
        List<SheetRow> rows = new ArrayList<>();
        int excelRow = 0;
        for (List<String> record : records) {
            excelRow++;
            SheetRow row = new SheetRow(excelRow, record);
            if (headers == null) {
                if (!row.isBlank()) {
                    headers = List.copyOf(record);
                }
                continue;
            }
            if (row.isBlank()) {
                continue;
            }
            if (rows.size() >= maxDataRows) {
                throw new SpreadsheetReadException(
                        SpreadsheetReadException.Reason.TOO_MANY_ROWS,
                        ImportLimits.tooManyRowsMessage(countDataRows(content)));
            }
            rows.add(row);
        }
        if (headers == null) {
            throw new SpreadsheetReadException(
                    SpreadsheetReadException.Reason.NO_HEADER_ROW,
                    "This file is empty - the first line needs to be the column headings.");
        }
        return SheetTable.of(headers, rows);
    }

    /**
     * The state machine. A field is either quoted or it is not; inside quotes everything is
     * literal except a doubled quote (the escape) and the quote that ends the field; outside
     * quotes the delimiter ends the field and any of CR, LF or CRLF ends the record.
     */
    private static List<List<String>> split(String text, char delimiter) {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean insideQuotes = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (insideQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        insideQuotes = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }
            if (c == '"') {
                insideQuotes = true;
            } else if (c == delimiter) {
                record.add(CellValues.clean(field.toString()));
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                record.add(CellValues.clean(field.toString()));
                field.setLength(0);
                records.add(record);
                record = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        // A file with no trailing newline still has a last record, and it is usually the one
        // carrying data somebody typed by hand.
        if (!field.isEmpty() || !record.isEmpty()) {
            record.add(CellValues.clean(field.toString()));
            records.add(record);
        }
        return records;
    }

    private static char sniffDelimiter(String text) {
        int lineEnd = text.indexOf('\n');
        String headerLine = lineEnd < 0 ? text : text.substring(0, lineEnd);
        char best = ',';
        long bestCount = 0;
        for (char candidate : CANDIDATE_DELIMITERS) {
            long count = headerLine.chars().filter(c -> c == candidate).count();
            if (count > bestCount) {
                best = candidate;
                bestCount = count;
            }
        }
        return best;
    }

    /** UTF-8, falling back to Windows-1252 when UTF-8 decoding produced replacement characters. */
    private static String decode(byte[] content) {
        String utf8 = new String(content, StandardCharsets.UTF_8);
        if (utf8.indexOf(REPLACEMENT_CHARACTER) < 0) {
            return utf8;
        }
        Charset windows1252 = Charset.availableCharsets().getOrDefault("windows-1252", StandardCharsets.ISO_8859_1);
        return new String(content, windows1252);
    }

}
