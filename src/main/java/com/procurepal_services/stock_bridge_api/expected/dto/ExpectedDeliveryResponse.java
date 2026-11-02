package com.procurepal_services.stock_bridge_api.expected.dto;

import com.procurepal_services.stock_bridge_api.entity.ExpectedDeliveryStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One expected delivery as a screen needs it. Nullable fields are left off the wire, so the
 * frontend compares with {@code == null}.
 *
 * @param title what the list calls it - "Tony Stores, due 22 Sep" - composed here rather than in
 *     the frontend, for the reason every other headline in this feature is: one place decides how
 *     it reads, and it stays the same on the list, the page and any future notification.
 * @param poNumber "PO-0007" when this is a purchase order; absent for one that was only written
 *     down. A screen decides "is this a purchase order?" by whether it is there.
 * @param revision 1 for the order as first made.
 * @param email what became of the latest attempt to email it to the supplier; absent when none was
 *     made.
 * @param outstandingLines how many lines still owe something. What "partly arrived" means, said
 *     as a number rather than as a fourth status.
 */
public record ExpectedDeliveryResponse(
        UUID id,
        ExpectedDeliveryStatus status,
        String title,
        UUID vendorId,
        String vendorName,
        LocalDate expectedDate,
        String reference,
        String note,
        List<Line> lines,
        int outstandingLines,
        BigDecimal total,
        boolean receivable,
        OffsetDateTime createdAt,
        // Multiple branches (V52): where it is going.
        UUID branchId,
        String poNumber,
        int revision,
        Email email) {

    /**
     * @param status SENT ("Emailed"), FAILED, BOUNCED or COMPLAINED - see PurchaseOrderEmailStatus.
     * @param problem a plain sentence for the order page when it did not go through; absent when it did.
     */
    public record Email(
            com.procurepal_services.stock_bridge_api.entity.PurchaseOrderEmailStatus status,
            String toAddress,
            OffsetDateTime sentAt,
            String problem) {
    }

    /**
     * @param unit the {@code UnitOptions.key} it was ordered in, sent back unchanged when
     *     receiving.
     * @param comesIn the words for that unit - "Bag · 50 kg" - so a screen never has to decode the
     *     key.
     * @param outstanding what is still owed on this line, already clamped at zero.
     */
    public record Line(
            UUID id,
            UUID productId,
            String productName,
            String sku,
            String unit,
            String comesIn,
            BigDecimal quantity,
            BigDecimal receivedQuantity,
            BigDecimal outstanding,
            BigDecimal price) {
    }
}
