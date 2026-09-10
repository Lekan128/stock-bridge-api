package com.procurepal_services.stock_bridge_api.imports.io;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * The one door into reading an uploaded file. Decides which underlying reader a file needs, and
 * enforces the size and row limits <em>in the order BULK_IMPORT_DESIGN.md section 11 requires</em>
 * - bytes first, then rows, then content.
 *
 * <h2>The ordering is the feature</h2>
 * Each check is cheaper than the one after it and rejects with a message that quotes the user's
 * own numbers:
 * <ol>
 *   <li>{@link MultipartFile#getSize()} - known before a byte is read, so an 80 MB file is
 *       refused without ever being loaded;</li>
 *   <li>{@link #countDataRows} - the sheet's {@code dimension} element, or a bare row count with
 *       no value resolved, so a 12,400-row file is refused in milliseconds with "This file has
 *       12,400 rows" rather than after a two-minute parse that was always going to be thrown
 *       away;</li>
 *   <li>the full read.</li>
 * </ol>
 * Doing this the other way round - parse, then check - is the failure mode section 11 calls out
 * by name, and it is a failure mode users experience as the product hanging.
 *
 * <h2>Format detection</h2>
 * By filename extension, because that is what the user actually chose and it is what their error
 * message has to talk about. A {@code .csv} goes to {@link CsvReader}; everything else is tried
 * as xlsx and fails with the deliberately-unchanged "The uploaded file is not a valid .xlsx file"
 * if it is not one. Content sniffing (a zip magic number) would be more clever and less useful:
 * the interesting failure is a {@code .xls} or an ODS renamed to {@code .xlsx}, and for those the
 * honest answer is the same either way.
 */
@Component
public class SpreadsheetReader {

    /**
     * Reads an uploaded file into the kind-agnostic {@link SheetTable} shape, applying every
     * limit on the way. Throws {@link SpreadsheetReadException} - and only that - for any failure
     * a user could have caused.
     */
    public SheetTable read(MultipartFile file) {
        requireWithinByteLimit(file.getSize());
        byte[] content = bytesOf(file);
        String filename = file.getOriginalFilename();
        requireWithinByteLimit(content.length);
        int dataRows = countDataRows(content, filename);
        if (!ImportLimits.isWithinRowLimit(dataRows)) {
            throw new SpreadsheetReadException(
                    SpreadsheetReadException.Reason.TOO_MANY_ROWS, ImportLimits.tooManyRowsMessage(dataRows));
        }
        return read(content, filename);
    }

    /** The byte-array entry point, for callers that already hold the content (M4's re-parse of a stored upload). */
    public SheetTable read(byte[] content, String filename) {
        return isCsv(filename)
                ? CsvReader.read(content, ImportLimits.MAX_ROWS)
                : XlsxStreamingReader.read(content, ImportLimits.MAX_ROWS);
    }

    /**
     * How many data rows a file has, cheaply, before anything is interpreted. Exposed rather than
     * kept private because the upload endpoint has a legitimate reason to ask on its own - it
     * chooses between a synchronous and an asynchronous commit on
     * {@link ImportLimits#ASYNC_ROW_THRESHOLD}, and that decision wants the count without the
     * rows.
     */
    public int countDataRows(byte[] content, String filename) {
        return isCsv(filename) ? CsvReader.countDataRows(content) : XlsxStreamingReader.countDataRows(content);
    }

    private void requireWithinByteLimit(long bytes) {
        if (!ImportLimits.isWithinByteLimit(bytes)) {
            throw new SpreadsheetReadException(
                    SpreadsheetReadException.Reason.FILE_TOO_LARGE, ImportLimits.tooLargeMessage(bytes));
        }
    }

    private boolean isCsv(String filename) {
        return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".csv");
    }

    private byte[] bytesOf(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            // The upload never arrived intact - a dropped connection mid-multipart, not a bad
            // spreadsheet. Surfaced as an I/O failure rather than dressed up as a validation
            // error, because telling the user their file is invalid when the network dropped
            // would send them off editing a file that is fine.
            throw new UncheckedIOException("Could not read the uploaded file", e);
        }
    }
}
