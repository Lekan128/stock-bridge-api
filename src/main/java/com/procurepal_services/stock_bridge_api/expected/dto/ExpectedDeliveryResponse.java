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
        OffsetDateTime createdAt) {

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
