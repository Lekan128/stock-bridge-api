package com.procurepal_services.stock_bridge_api.imports.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Rows pasted rather than uploaded (BULK_IMPORT_CX_PLAN.md task 3.2). The delivery fields are the
 * same three the upload screen asks for, and mean the same thing.
 *
 * @param text the block exactly as it was pasted. Tab, comma and semicolon separated all work,
 *     with or without a header row.
 */
public record PasteRequest(
        @NotBlank(message = "Paste the rows first.") @Size(max = 2_000_000) String text,
        String kind,
        String mode,
        String deliveryDate,
        @Size(max = 200) String invoiceNo,
        String vendorId,
        // Multiple branches (V52): the branch this is for - see ImportSession.branchId.
        UUID branchId) {

    /** The shape before {@code branchId} (multiple branches, Phase 1). */
    public PasteRequest(String text, String kind, String mode, String deliveryDate, String invoiceNo, String vendorId) {
        this(text, kind, mode, deliveryDate, invoiceNo, vendorId, null);
    }
}
