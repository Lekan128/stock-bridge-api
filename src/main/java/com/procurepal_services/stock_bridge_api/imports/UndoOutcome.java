package com.procurepal_services.stock_bridge_api.imports;

import java.util.List;

/**
 * The answer to "undo this import": either it was undone, or a clean explanation of what is in
 * the way - BULK_IMPORT_CONTRACT.md section 4's {@code UndoBlockedResponse} when blocked.
 *
 * <h2>Blocked is a message, not a failure</h2>
 * Design 6.6 is emphatic about this and it is the whole reason the type has two shapes rather
 * than one plus an exception. "Three of these forty deliveries have already been sold from, so
 * this import can't be undone as a batch" is a complete, actionable answer with a link to the
 * three; a stack trace or a generic 409 is not. Contract section 8.10 draws the line the refusal
 * protects: undo never deletes ledger rows, so where a lot has been drawn from the honest answer
 * is to say so, not to quietly unpick the allocations that recorded a real sale.
 *
 * <p>The frontend detects this exact body by {@code typeof message === 'string' &&
 * Array.isArray(blockers)}. A plain {@code ApiError} on the same 409 degrades to a generic toast
 * and the panel built for this never renders - so the two fields are load-bearing, not
 * decorative.
 *
 * @param blocked whether the undo was refused.
 * @param message the sentence. On success, what was reversed; on refusal, why it could not be.
 * @param blockers what stands in the way, empty on success.
 * @param lines past-tense summary lines for the result screen after a successful undo.
 * @param productsDeactivated created products switched off (catalog undo).
 * @param fieldsReverted updated products restored from their pre-update snapshot.
 * @param movementsReversed compensating ADJUSTMENT rows written (stock-in undo).
 */
public record UndoOutcome(
        boolean blocked,
        String message,
        List<Blocker> blockers,
        List<CommitPreview.Line> lines,
        int productsDeactivated,
        int fieldsReverted,
        int movementsReversed) {

    public UndoOutcome {
        blockers = List.copyOf(blockers);
        lines = List.copyOf(lines);
    }

    /**
     * @param excelRow the spreadsheet row, so the user can find it in their own file.
     * @param label the product's name. What actually gets rendered.
     * @param reason short phrase - "Already sold from".
     * @param entityId never rendered; used only to build a link to the thing.
     */
    public record Blocker(int excelRow, String label, String reason, String entityId) {
    }

    public static UndoOutcome blocked(String message, List<Blocker> blockers) {
        return new UndoOutcome(true, message, blockers, List.of(), 0, 0, 0);
    }

    public static UndoOutcome done(
            String message,
            List<CommitPreview.Line> lines,
            int productsDeactivated,
            int fieldsReverted,
            int movementsReversed) {
        return new UndoOutcome(
                false, message, List.of(), lines, productsDeactivated, fieldsReverted, movementsReversed);
    }
}
