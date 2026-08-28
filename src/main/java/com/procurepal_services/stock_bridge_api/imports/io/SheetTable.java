package com.procurepal_services.stock_bridge_api.imports.io;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A whole sheet as the import pipeline sees it: the header row, and the data rows under it.
 * Produced identically by the xlsx and CSV readers, which is what lets everything downstream -
 * this module's product and stock-in parsers, and M4's session engine - be written once.
 *
 * <h2>Two views of the header, and both are needed</h2>
 * {@link #headers()} is what the file literally said, in column order, so the review screen's
 * column-mapping UI can show the user their own words back. {@link #columnIndexes()} is the
 * normalized form keyed to its column index, so a parser can ask for {@code "unit_of_measure"}
 * without caring that the file said {@code "Unit Of Measure"}. Keeping the raw form alongside the
 * normalized one is what makes it possible to say "we mapped your column <em>Supplier</em> to
 * Vendor name" instead of silently renaming it.
 */
public record SheetTable(List<String> headers, Map<String, Integer> columnIndexes, List<SheetRow> rows) {

    public SheetTable {
        // Null-tolerant for the same reason SheetRow is: a sheet may have an unnamed column
        // between two named ones, and losing the position would shift every column after it.
        headers = Collections.unmodifiableList(new ArrayList<>(headers));
        columnIndexes = Map.copyOf(columnIndexes);
        rows = List.copyOf(rows);
    }

    /**
     * Builds the normalized header index from the raw header cells. Later duplicates of the same
     * normalized name lose to the first: a file with two columns both normalizing to
     * {@code "sku"} is a mistake, and the left-most one is what the user was looking at when they
     * typed the data under it.
     */
    static SheetTable of(List<String> rawHeaders, List<SheetRow> rows) {
        Map<String, Integer> indexes = new LinkedHashMap<>();
        for (int i = 0; i < rawHeaders.size(); i++) {
            String normalized = HeaderNames.normalize(rawHeaders.get(i));
            if (normalized != null) {
                indexes.putIfAbsent(normalized, i);
            }
        }
        return new SheetTable(rawHeaders, indexes, rows);
    }

    /** The column index for a normalized header name, or null when the file does not have that column. */
    public Integer columnIndex(String normalizedHeaderName) {
        return columnIndexes.get(normalizedHeaderName);
    }

    public boolean hasColumn(String normalizedHeaderName) {
        return columnIndexes.containsKey(normalizedHeaderName);
    }

    /** The value of one named column on one row, or null when either the column or the value is absent. */
    public String value(SheetRow row, String normalizedHeaderName) {
        Integer index = columnIndex(normalizedHeaderName);
        return index == null ? null : row.cell(index);
    }
}
