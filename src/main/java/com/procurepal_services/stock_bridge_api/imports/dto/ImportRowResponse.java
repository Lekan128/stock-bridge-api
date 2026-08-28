package com.procurepal_services.stock_bridge_api.imports.dto;

import com.procurepal_services.stock_bridge_api.entity.ImportRowStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One row on the review grid - BULK_IMPORT_CONTRACT.md section 4's {@code ImportRowResponse}.
 *
 * <p>{@code raw} and {@code normalized} are both sent, and both are needed. {@code normalized} is
 * what the cell editor binds to and what commit will read; {@code raw} is what the file actually
 * said, which is what lets the grid show the original beneath a repaired cell and lets the user
 * check a fix against their own typing. Design 6.1 makes the same point about keeping the two
 * side by side in the table: a repair is only inspectable if the thing it replaced survives.
 *
 * <p>Both are keyed by field key here even though the persisted {@code raw} is keyed by
 * spreadsheet header - the projection happens on the way out, so a change to the column mapping
 * changes what this shows without ever rewriting the immutable record of the file.
 *
 * @param errors blocking problems. Each carries {@code bulkFixCount} pre-computed, because
 *     contract section 4 requires the count to arrive with the error rather than being derived
 *     client-side - "[Fix all 12 KGS rows]" is the single highest-value interaction on this
 *     screen (design 9.3) and it cannot be built from a page of fifty rows.
 * @param warnings non-blocking ones. An update row's ignored quantity lives here, and section
 *     8.8 makes its presence mandatory rather than cosmetic.
 * @param resolvedEntityLabel the name behind {@code resolvedEntityId}. The id is sent so links
 *     can be built; only the label is ever rendered (section 8.7).
 * @param continuationOf the parent's {@code excelRow} when this is a section 7.1 continuation
 *     row - a repeated SKU carrying a second supplier. The grid nests on it, which is what makes
 *     the file's structure legible to a user who has never heard of the convention.
 * @param outcome null until the commit has run.
 */
public record ImportRowResponse(
        UUID id,
        int excelRow,
        ImportRowStatus status,
        Map<String, Object> raw,
        Map<String, Object> normalized,
        List<Error> errors,
        List<Warning> warnings,
        UUID resolvedEntityId,
        String resolvedEntityLabel,
        Integer continuationOf,
        String outcome) {

    /**
     * @param suggestion the one-click fix, or null when there is nothing to guess.
     * @param bulkFixCount rows sharing the same (column, raw value, message). Non-null on every
     *     error that has one.
     */
    public record Error(String column, String message, Suggestion suggestion, Integer bulkFixCount) {
    }

    public record Warning(String column, String message) {
    }

    public record Suggestion(String value, String label) {
    }
}
