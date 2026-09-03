package com.procurepal_services.stock_bridge_api.imports.dto;

import com.procurepal_services.stock_bridge_api.entity.ImportRowStatus;
import com.procurepal_services.stock_bridge_api.imports.ImportFieldDescriptor;
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
 * @param fieldOptions per-row narrowing of an ENUM column's choices, keyed by field key -
 *     UNIT_UX_CONTRACT.md section 6.2. Null or absent for a row that has nothing to narrow, and
 *     the grid then falls back to the field descriptor's kind-wide {@code options}. Populated for
 *     {@code counted_in} on every stock-in row whose product resolved, which turns a thirty-option
 *     select into a two-option one - the Flatfile pattern BULK_IMPORT_DESIGN.md section 4 already
 *     cites, applied to the column that needed it.
 *     <p>It lives on the ROW and not on {@link ImportFieldDescriptor} because the answer is a fact
 *     about this row's product, and section 6.1 keeps the descriptor's {@code options} as the
 *     kind-wide fallback precisely so the two cannot be confused.
 * @param baseQuantityText the server-composed {@code "= 2,000 kg"} rendered under the quantity
 *     cell - section 6.2, and non-negotiable 3 on the review grid: what the user typed and what
 *     the ledger will record, together. Null when there is nothing to convert, which is the
 *     ordinary case of a row counted in its product's own stock unit.
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
        String outcome,
        Map<String, List<ImportFieldDescriptor.Option>> fieldOptions,
        String baseQuantityText) {

    /**
     * @param suggestion the one-click fix, or null when there is nothing to guess.
     * @param bulkFixCount rows sharing the same (column, raw value, message). Non-null on every
     *     error that has one.
     */
    public record Error(String column, String message, Suggestion suggestion, Integer bulkFixCount) {
    }

    /**
     * A non-blocking problem.
     *
     * <h2>Why this gained a suggestion and a count, when the contract said warnings would not</h2>
     * BULK_IMPORT_CONTRACT.md section 4 states that warnings deliberately carry no
     * {@code bulkFixCount}, and the reasoning was sound for the only warning that existed then:
     * an update row's ignored quantity is informational, and there is nothing for a bulk form to
     * apply. UNIT_UX_CONTRACT.md section 5.1 then introduced a warning that is the opposite shape
     * - "20 kg - did you mean 20 bags (1,000 kg)?" - and asks for it to carry a one-click bulk
     * fix, because a file where one row means packs almost always has forty more like it.
     *
     * <p>So both fields are nullable and additive: every warning that predates this change sends
     * both as null and behaves exactly as it did. A warning carries a count only when it also
     * carries a suggestion, i.e. only when there is a concrete value a click would apply. That
     * rule is what keeps section 4's intent - no bulk button on a warning with nothing to fix -
     * while letting the one warning that does have something to fix offer it.
     *
     * @param suggestion the value one click would write into the cell, or null.
     * @param bulkFixCount how many rows share this same (column, code, raw value), non-null only
     *     alongside a suggestion.
     */
    public record Warning(String column, String message, Suggestion suggestion, Integer bulkFixCount) {
    }

    public record Suggestion(String value, String label) {
    }
}
