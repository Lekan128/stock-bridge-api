package com.procurepal_services.stock_bridge_api.imports;

import java.util.List;

/**
 * One distinct value in the file that nothing in the tenant's data matches, collapsed across
 * every row that carries it - BULK_IMPORT_CONTRACT.md section 4's {@code UnresolvedValue},
 * serialized verbatim onto {@code ImportSessionResponse.unresolvedValues}.
 *
 * <p>This is design 6.4, the part that decides whether the feature feels smart or feels like a
 * form. Per-row resolution of the same supplier name forty-seven times is not a worse version of
 * this screen; it is a different and much worse product, and the reason to collapse is not
 * efficiency but that the user only ever had one thought.
 *
 * @param column the field key the value came from. Never shown - {@code columnLabel} is.
 * @param columnLabel what to call that column in the question ("Supplier", not
 *     {@code vendor_name}), per contract section 8.7.
 * @param value the text exactly as it appeared in the file, quoted back so the user recognises
 *     their own typing.
 * @param rowCount how many rows this one answer settles. It is in the record rather than counted
 *     client-side because contract section 4 requires every bulk affordance to carry its count
 *     from the server.
 * @param excelRows the spreadsheet row numbers, so "show me which rows" is answerable without a
 *     second request. Capped by the caller - a value on 4,000 rows does not need 4,000 numbers.
 * @param kind what sort of thing is being resolved, which is what picks the resolution card's
 *     shape: a VENDOR card offers "add this supplier", a PRODUCT card also collects a unit of
 *     measure, a UNIT card is a plain picker.
 * @param suggestions closest existing matches, best first, each with a hint that says why it is
 *     a plausible answer ("3 products").
 * @param allowCreateNew whether "add it" is offered at all - false when the caller lacks the
 *     permission that inline creation is gated on (design 13.2).
 * @param allowBlank whether "leave blank" is a legal answer, i.e. whether the column is optional.
 * @param allowSkipRows whether "skip those rows" is offered. True only where dropping the rows
 *     leaves a coherent import - an unknown product on a stock-in row, never a unit on a row the
 *     user clearly meant to include.
 * @param resolution the accepted answer once given, echoed back so the card renders as settled.
 */
public record UnresolvedValue(
        String column,
        String columnLabel,
        String value,
        int rowCount,
        List<Integer> excelRows,
        Kind kind,
        List<Suggestion> suggestions,
        boolean allowCreateNew,
        boolean allowBlank,
        boolean allowSkipRows,
        ValueResolution resolution) {

    public UnresolvedValue {
        excelRows = List.copyOf(excelRows);
        suggestions = List.copyOf(suggestions);
    }

    public enum Kind {
        VENDOR,
        UNIT,
        PRODUCT
    }

    /**
     * One candidate answer.
     *
     * @param id the entity behind it. Sent because the resolution has to name something; never
     *     rendered, per contract section 8.7.
     * @param label what the user sees.
     * @param hint why this is being offered - "3 products", "same SKU". Nullable.
     * @param score 0..1 similarity, so the card can order and, if it wants, de-emphasise weak
     *     guesses rather than presenting a bad match with the same confidence as a good one.
     */
    public record Suggestion(String id, String label, String hint, double score) {
    }

    public UnresolvedValue withResolution(ValueResolution accepted) {
        return new UnresolvedValue(
                column, columnLabel, value, rowCount, excelRows, kind, suggestions,
                allowCreateNew, allowBlank, allowSkipRows, accepted);
    }
}
