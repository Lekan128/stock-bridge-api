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
        OffsetDateTime cancelDecidedAt,
        /** Step 12 (V96): which account a transfer came into ("GTBank"); absent otherwise. Never on a receipt. */
        java.util.UUID paymentAccountId,
        String paymentAccountName,
        /** A table's bill (V99): its table ("Table 5", "VIP") or the name it was opened for; absent for a counter sale. */
        String billFor,
        /** Step 10 (V100): its charges and taxes as they stood when it was made, with what each came to. */
        java.util.List<com.procurepal_services.stock_bridge_api.selling.ChargeService.SaleCharge> charges,
        /** What the customer paid: the food and drinks ({@code total}) plus every charge added on top. */
        java.math.BigDecimal totalToPay) {
}
