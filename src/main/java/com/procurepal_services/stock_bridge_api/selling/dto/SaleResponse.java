package com.procurepal_services.stock_bridge_api.selling.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One sale - every line recorded under its id - as it stands now.
 *
 * @param total what the lines that are not cancelled went for; lines with no price are left out
 *     and counted in {@code unpricedLineCount}, never added as zero.
 */
public record SaleResponse(
        UUID saleId,
        OffsetDateTime occurredAt,
        UUID recordedByUserId,
        String recordedByName,
        List<SaleLineResponse> lines,
        BigDecimal total,
        int lineCount,
        int cancelledLineCount,
        int unpricedLineCount,
        /** How the customer paid (selling till Step 1); absent for a sale from before payments were recorded. */
        com.procurepal_services.stock_bridge_api.selling.SalePaymentMethod paymentMethod,
        /** Where it was sold, at a company with several branches - for the receipt. */
        String branchName,
        /**
         * Cancelling (selling till Step 3): REQUESTED (a cashier asked, waiting), DECLINED (the sale stands) or
         * CANCELLED (the whole sale); absent when nobody has asked or cancelled it.
         */
        String cancelState,
        com.procurepal_services.stock_bridge_api.selling.SaleCancelReason cancelReason,
        String cancelNote,
        String cancelRequestedByName,
        OffsetDateTime cancelRequestedAt,
        /** Who cancelled it, or declined the request. */
        String cancelDecidedByName,
        OffsetDateTime cancelDecidedAt) {
}
