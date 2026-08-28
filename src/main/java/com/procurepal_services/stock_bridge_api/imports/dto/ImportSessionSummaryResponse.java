package com.procurepal_services.stock_bridge_api.imports.dto;

import com.procurepal_services.stock_bridge_api.entity.ImportKind;
import com.procurepal_services.stock_bridge_api.entity.ImportStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One line of the recent-imports list - BULK_IMPORT_CONTRACT.md section 4's
 * {@code ImportSessionSummaryResponse}.
 *
 * <p>Carries {@code summaryText} deliberately, so the frontend never string-builds a count. Same
 * rule as the preview and result lines, and the same reason: "38 created, 4 updated" is copy,
 * copy has rules (design 9.6), and rules applied in a list component are rules that will
 * eventually be applied differently in two list components.
 */
public record ImportSessionSummaryResponse(
        UUID id,
        ImportKind kind,
        ImportStatus status,
        String originalFilename,
        int rowCount,
        Integer createdCount,
        Integer updatedCount,
        String summaryText,
        String uploadedByName,
        OffsetDateTime createdAt,
        OffsetDateTime committedAt,
        boolean undoable) {
}
