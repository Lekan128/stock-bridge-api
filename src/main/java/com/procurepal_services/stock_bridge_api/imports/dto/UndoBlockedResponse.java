package com.procurepal_services.stock_bridge_api.imports.dto;

import java.util.List;

/**
 * The 409 body of {@code POST /api/imports/{id}/undo} - BULK_IMPORT_CONTRACT.md section 4's
 * {@code UndoBlockedResponse}, and one of the few places where the exact shape of a body is
 * itself a requirement rather than an implementation detail.
 *
 * <p>The frontend recognises it by {@code typeof message === 'string' &&
 * Array.isArray(blockers)}. Answering this 409 with a plain {@code ApiError} would still be a
 * 409 and would still say something true, and the undo-blocked panel would never render: the
 * user would get a generic toast and no way to find out which three of their forty deliveries
 * are in the way. That is why this is a distinct type with a distinct handler rather than a
 * message on the shared error record.
 *
 * <p>Design 6.6: the blocked case is not a failure. It is the sentence "3 of these 40 deliveries
 * have already been sold from, so this import can't be undone as a batch", with a link to the
 * three - because deleting ledger rows to make the button work would defeat the entire
 * multi-vendor traceability design (contract section 8.10).
 */
public record UndoBlockedResponse(String message, List<Blocker> blockers) {

    /** @param entityId never rendered; used only to link to the thing that is in the way. */
    public record Blocker(int excelRow, String label, String reason, String entityId) {
    }
}
