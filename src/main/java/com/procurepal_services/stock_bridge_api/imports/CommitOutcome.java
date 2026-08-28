package com.procurepal_services.stock_bridge_api.imports;

import java.util.List;

/**
 * What a commit did, in the same prose shape the confirm screen used to predict it -
 * BULK_IMPORT_CONTRACT.md section 4's {@code ImportResultResponse}, minus the fields the engine
 * fills in (session id, status, urls, whether an undo is still possible).
 *
 * <p>Past tense of {@link CommitPreview} on purpose. A user who read "12 new products" on the
 * confirm screen and then reads "12 products created" on the result screen can check the two
 * against each other at a glance, which is the cheapest possible reassurance that the thing they
 * agreed to is the thing that happened.
 *
 * <h2>Why the counts are separate fields and not just derived from the lines</h2>
 * The lines are copy and will be reworded. {@code createdCount} and its siblings are the numbers
 * the result screen's links and the recent-imports list are built from, and the frontend reads
 * them directly. Deriving them by parsing a sentence would make every copy change a wire break.
 *
 * @param headline "Imported 42 rows".
 * @param lines the same {@code {key,label,count,text}} shape as the preview.
 * @param createdCount rows that created their entity.
 * @param updatedCount rows that updated one.
 * @param skippedCount rows deliberately not acted on - user-skipped, blank-quantity stock-in
 *     rows (contract section 8.11), and section 7.1 continuation rows, which are folded into
 *     their parent rather than being an outcome of their own.
 * @param failedCount rows that could not be written. Zero on any successful commit, because the
 *     transaction is all-or-nothing (design 6.5); non-zero only on the report of a failed run.
 * @param vendorsCreated suppliers added to the directory inline (design 13.2). Surfaced
 *     separately because it must appear explicitly and never silently.
 * @param productsCreated products created by a stock-in row's inline escape hatch (design 6.7).
 * @param movementsCreated ledger rows written. The number that proves section 3's fix is real:
 *     no quantity reaches {@code products.quantity_on_hand} without one of these.
 */
public record CommitOutcome(
        String headline,
        List<CommitPreview.Line> lines,
        int createdCount,
        int updatedCount,
        int skippedCount,
        int failedCount,
        int vendorsCreated,
        int productsCreated,
        int movementsCreated) {

    public CommitOutcome {
        lines = List.copyOf(lines);
    }
}
