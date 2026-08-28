package com.procurepal_services.stock_bridge_api.imports;

import com.procurepal_services.stock_bridge_api.entity.ImportMode;
import com.procurepal_services.stock_bridge_api.entity.ImportSession;
import com.procurepal_services.stock_bridge_api.entity.ImportSessionRow;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything {@link ImportRowHandler#validate} is allowed to see about one row -
 * BULK_IMPORT_CONTRACT.md section 2's {@code RowContext}.
 *
 * <p>Deliberately narrow. A handler gets the row's values, the session it belongs to, the
 * answers already given to the file's distinct-value questions, and a batch-scoped cache. It
 * does not get the request, the acting user's authorities, or anything else that would let a
 * validation decision depend on who is looking - because the same file must validate the same
 * way whether it is being reviewed, previewed or committed.
 *
 * @param session the escrow row, for {@code kind}, {@code mode} and the tenant.
 * @param row the persisted row. Handlers read its {@code excelRow} for error messages; its
 *     {@code raw} is reachable but should be read through {@link #rawText} instead, which has
 *     already been projected through the column mapping into field keys.
 * @param input field key to effective value - the file's cell overlaid with the user's repairs.
 *     Values are cleaned {@code String}s from the sheet, or already-coerced objects from a
 *     previous pass; the accessors below cope with both, which is what makes re-validation
 *     idempotent.
 * @param rawText field key to the original cell text, for messages that quote what was typed.
 * @param valueMappings answers already accepted for this file.
 * @param cache per-pass memo; see {@link ImportBatchCache}.
 * @param actingUserId who is doing this. Carried for attribution on writes, never for a
 *     validation decision.
 */
public record RowContext(
        ImportSession session,
        ImportSessionRow row,
        Map<String, Object> input,
        Map<String, String> rawText,
        ValueMappings valueMappings,
        ImportBatchCache cache,
        UUID actingUserId) {

    public UUID tenantId() {
        return session.getClientId();
    }

    public ImportMode mode() {
        return session.getMode();
    }

    public int excelRow() {
        return row.getExcelRow();
    }

    /** The effective value as text, cleaned to null when blank. Never returns the empty string. */
    public String text(String field) {
        Object value = input.get(field);
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    /** What the file actually said, for quoting back. Falls back to the effective value. */
    public String rawTextOf(String field) {
        String raw = rawText.get(field);
        return raw != null ? raw : text(field);
    }

    public boolean has(String field) {
        return text(field) != null;
    }

    /**
     * True when the user has explicitly emptied this cell, as opposed to it never having been
     * filled. The distinction matters on an update row: clearing a description is a change the
     * user asked for, while a blank cell in a file that has no description column at all is not.
     */
    public boolean explicitlyBlank(String field) {
        return input.containsKey(field) && input.get(field) == null;
    }

    public Optional<BigDecimal> decimal(String field) {
        Object value = input.get(field);
        if (value instanceof BigDecimal already) {
            return Optional.of(already);
        }
        if (value instanceof Number number) {
            return Optional.of(new BigDecimal(number.toString()));
        }
        String text = text(field);
        return text == null
                ? Optional.empty()
                : com.procurepal_services.stock_bridge_api.imports.io.NumberValues.parseDecimal(text);
    }

    public Optional<ValueResolution> resolution(String column) {
        String value = rawTextOf(column);
        return value == null ? Optional.empty() : valueMappings.resolutionFor(column, value);
    }
}
