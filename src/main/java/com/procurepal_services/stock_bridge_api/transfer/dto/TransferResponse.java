package com.procurepal_services.stock_bridge_api.transfer.dto;

import com.procurepal_services.stock_bridge_api.entity.StockTransferStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A waybill as both branches see it (MULTI_BRANCH_UX_PLAN.md sections 5.4-5.7).
 *
 * @param waybill its number as people say it, "WB-BOL-0042".
 * @param shareText the waybill as plain text, for WhatsApp - readable in a chat, on any phone.
 * @param missing what was reported missing or damaged and is not yet checked: shown at both branches.
 */
public record TransferResponse(
        UUID id,
        String waybill,
        int number,
        UUID fromBranchId,
        String fromBranchName,
        UUID toBranchId,
        String toBranchName,
        StockTransferStatus status,
        String sentByName,
        OffsetDateTime sentAt,
        String carriedByName,
        String carriedByPhone,
        String note,
        String receivedByName,
        OffsetDateTime receivedAt,
        int missing,
        String missingCheckedByName,
        OffsetDateTime missingCheckedAt,
        String missingNote,
        List<Line> lines,
        String shareText) {

    /**
     * Quantities in the stock unit; {@code enteredUnit}/{@code enteredQuantity} as it was typed, and
     * {@code sentText} the two read out - "10 crates", "240 pieces" - as the waybill prints it.
     */
    public record Line(
            UUID productId,
            String productName,
            String sku,
            String unitOfMeasure,
            int quantitySent,
            int quantityReceived,
            int quantityMissing,
            int quantityDamaged,
            int onTheWay,
            String enteredUnit,
            BigDecimal enteredQuantity,
            String sentText) {
    }
}
