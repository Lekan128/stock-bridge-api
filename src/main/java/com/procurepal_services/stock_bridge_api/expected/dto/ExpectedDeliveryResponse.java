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
 * @param share what the buyer needs to send the order themselves - the link, the message and the
 *     WhatsApp address. Only on a single order (not the list), and only for a purchase order.
 * @param email what became of the latest attempt to email it to the supplier; absent when none was
 *     made.
 * @param waitingToBeSent a purchase order whose current revision the supplier has not been given -
 *     never sent, or changed since. What the list's "To send" means.
 * @param sentVia EMAIL, WHATSAPP, MESSAGE or PHONE - how the supplier last got it; absent if never.
 * @param total the lines' amounts plus delivery; absent when no line has a price.
 * @param deliveryCharge what the supplier charges to bring it (V77); absent when not said, zero when free.
 * @param approval WAITING, CHANGE_ASKED or APPROVED (V78); absent when no approval was involved.
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
        Email email,
        Share share,
        // Redesign 2026-11-04 (V76): who made it, and whether the supplier has the current revision.
        String createdByName,
        OffsetDateTime sentAt,
        String sentVia,
        boolean waitingToBeSent,
        List<Receipt> receipts,
        BigDecimal deliveryCharge,
        Approval approval) {

    /**
     * Where an order stands on approval, with who and when for the History list.
     *
     * @param status WAITING, CHANGE_ASKED or APPROVED.
     * @param note what whoever asked wrote for the approver.
     * @param changeNote what the approver asked to be changed.
     */
    public record Approval(
            String status,
            String askedByName,
            OffsetDateTime askedAt,
            String note,
            String approvedByName,
            OffsetDateTime approvedAt,
            String changeAskedByName,
            OffsetDateTime changeAskedAt,
            String changeNote,
            UUID askedBy) {
    }

    /**
     * One delivery received against this order, for the order's history. Only on a single order.
     *
     * @param importId the delivery's own record, for "See delivery".
     */
    public record Receipt(
            UUID importId, OffsetDateTime receivedAt, String receivedByName, int lineCount, List<SentBack> sentBack) {

        public Receipt(UUID importId, OffsetDateTime receivedAt, String receivedByName, int lineCount) {
            this(importId, receivedAt, receivedByName, lineCount, List.of());
        }
    }

    /**
     * What went back with the driver on one delivery (V79): "3 bags · Damaged".
     *
     * @param comesIn the words for the unit, as on the order's lines.
     * @param reason WRONG_ITEM, DAMAGED, EXPIRED, NOT_ORDERED, PRICE or OTHER.
     */
    public record SentBack(UUID productId, String productName, String unit, String comesIn, BigDecimal quantity,
            String reason, String note) {
    }

    /**
     * @param url the public page the supplier opens, when the server knows the app's address.
     * @param message the message to paste or send, written in the buyer's voice, link included.
     *     Absent in the list, which only needs to know whether it was shared.
     * @param whatsappUrl opens WhatsApp to the supplier with the message typed; absent when the
     *     supplier has no phone number WhatsApp could use.
     * @param whatsappOpenedAt / messageCopiedAt when the buyer last did that. We only ever say
     *     "you opened WhatsApp" - never that it was delivered or read.
     */
    public record Share(
            String token,
            String url,
            String message,
            String whatsappUrl,
            OffsetDateTime whatsappOpenedAt,
            OffsetDateTime messageCopiedAt) {
    }

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
