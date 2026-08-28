package com.procurepal_services.stock_bridge_api.imports.dto;

import com.procurepal_services.stock_bridge_api.entity.ImportStatus;
import java.util.List;
import java.util.UUID;

/**
 * BULK_IMPORT_CONTRACT.md section 4's {@code ImportResultResponse} - what happened, past tense.
 *
 * <p>Served from two places that must agree: {@code POST /commit}'s 200 body, and
 * {@code GET /result}. They are separate endpoints because the result screen is a real URL that
 * has to survive a refresh and a shared link, while a commit only ever answers once - and
 * because the async path (contract section 6's {@code ASYNC_ROW_THRESHOLD}) answers 202 with no
 * body at all, leaving {@code GET /result} as the only way the frontend can ever learn what
 * happened. Both are built by the same method for exactly that reason.
 *
 * @param undoable whether the undo button renders. False once the batch has been undone, and
 *     false for a kind or a state where reversal is not possible.
 * @param undoBlockedReason why not, when {@code undoable} is false and there is something worth
 *     saying. Shown as a note beside the absent button rather than leaving the user to wonder.
 * @param reportUrl relative, and NOT usable as an {@code <a href>} - every one of these
 *     endpoints is authenticated and a bare link sends no bearer token. The frontend fetches it
 *     as a blob through its authed client; the field exists so the path is not hardcoded there.
 * @param targetUrl where "View products" goes, already filtered to what this run touched.
 */
public record ImportResultResponse(
        UUID sessionId,
        ImportStatus status,
        String headline,
        List<CommitPreviewResponse.Line> lines,
        int createdCount,
        int updatedCount,
        int skippedCount,
        int failedCount,
        int vendorsCreated,
        int productsCreated,
        int movementsCreated,
        boolean undoable,
        String undoBlockedReason,
        String reportUrl,
        String targetUrl) {
}
