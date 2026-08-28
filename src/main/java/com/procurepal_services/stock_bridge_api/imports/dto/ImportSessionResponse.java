package com.procurepal_services.stock_bridge_api.imports.dto;

import com.procurepal_services.stock_bridge_api.entity.ImportKind;
import com.procurepal_services.stock_bridge_api.entity.ImportMode;
import com.procurepal_services.stock_bridge_api.entity.ImportStatus;
import com.procurepal_services.stock_bridge_api.imports.ImportFieldDescriptor;
import com.procurepal_services.stock_bridge_api.imports.UnresolvedValue;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BULK_IMPORT_CONTRACT.md section 4's {@code ImportSessionResponse}, field for field.
 *
 * <p>This is the object the whole review screen is derived from. Two of its fields carry more
 * weight than their size suggests:
 *
 * <p>{@code needsMapping} is the only thing that can force the mapping step (design 6.2), so it
 * is false for anyone using our own template and the step simply does not exist for them.
 *
 * <p>{@code fields} lets the grid render columns it has never heard of. Contract section 7 pins
 * the frontend's status-to-step mapping as derived from this response and never held in
 * component state, so a refresh or a shared link lands on the right step - which only works if
 * everything the step needs is here.
 *
 * @param columnMapping header to field key, with an explicit null for every header that resolved
 *     to nothing. The nulls matter: the mapping screen renders one row per column in the file,
 *     and a header omitted entirely would be a column the user cannot re-point.
 * @param requiredFieldsMissing field keys, resolved to labels by the frontend before display -
 *     never printed raw, per contract section 8.7.
 */
public record ImportSessionResponse(
        UUID id,
        ImportKind kind,
        ImportMode mode,
        ImportStatus status,
        String originalFilename,
        int rowCount,
        int validCount,
        int errorCount,
        int warningCount,
        int skippedCount,
        boolean needsMapping,
        Map<String, String> columnMapping,
        List<String> unmappedHeaders,
        List<String> requiredFieldsMissing,
        List<ImportFieldDescriptor> fields,
        List<UnresolvedValue> unresolvedValues,
        String uploadedByName,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt,
        OffsetDateTime committedAt) {
}
