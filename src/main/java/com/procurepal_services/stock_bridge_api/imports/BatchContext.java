package com.procurepal_services.stock_bridge_api.imports;

import com.procurepal_services.stock_bridge_api.entity.ImportMode;
import com.procurepal_services.stock_bridge_api.entity.ImportSession;
import java.util.List;
import java.util.UUID;

/**
 * The whole file at once - BULK_IMPORT_CONTRACT.md section 2's {@code BatchContext}, and the
 * argument to every phase that cannot be decided one row at a time.
 *
 * <p>Four of the SPI's methods take this rather than a row, and each for the same reason: the
 * question they answer is about the file, not about a line of it. Whether a repeated SKU is a
 * duplicate or a second supplier (section 7.1) depends on the rows around it. Whether
 * {@code "Dangote Ltd"} is worth asking about depends on how many rows carry it. What the
 * confirm screen says is a sentence about the batch. And the commit is one transaction over all
 * of it, which is what makes the undo in section 6.6 possible at all.
 *
 * @param session the escrow row.
 * @param states every row in the file, in spreadsheet order, including ones the user has
 *     skipped. Skipped rows are present rather than filtered because
 *     {@code ImportResultReportWriter} is handed every row - completeness is the point of the
 *     report - and because a batch rule has to see a skipped duplicate to know the row that
 *     survived it is no longer duplicated.
 * @param valueMappings answers already accepted for this file.
 * @param cache per-pass memo shared with every {@code validate} call in the same pass.
 * @param actingUserId who is doing this; the attribution on every row the commit writes.
 */
public record BatchContext(
        ImportSession session,
        List<ImportRowState> states,
        ValueMappings valueMappings,
        ImportBatchCache cache,
        UUID actingUserId) {

    public UUID tenantId() {
        return session.getClientId();
    }

    public ImportMode mode() {
        return session.getMode();
    }

    /** Rows the commit will act on: not skipped, and free of blocking errors. */
    public List<ImportRowState> committable() {
        return states.stream().filter(ImportRowState::isCommittable).toList();
    }

    /** Committable rows that are not section 7.1 continuations - one per product. */
    public List<ImportRowState> primaryCommittable() {
        return committable().stream()
                .filter(state -> state.getContinuationOf() == null)
                .toList();
    }

    public List<ImportRowState> skipped() {
        return states.stream().filter(ImportRowState::isSkipped).toList();
    }
}
