package com.procurepal_services.stock_bridge_api.expected.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * "This is what we ordered." The shape mirrors {@code DeliveryRequest} on purpose - same supplier,
 * same date, same {product, unit, quantity, price} lines - because the screen that records an
 * expectation and the screen that records its arrival are the same screen with a different verb.
 *
 * @param expectedDate when the supplier said it would come - or, on a purchase order, when it is
 *     needed. Optional.
 * @param purchaseOrder true to make this a purchase order: it gets a number and a frozen copy of
 *     what the supplier is shown. Needs a supplier. Since 2026-11-06 (plan section 11.4) its lines
 *     carry their price per unit as ordered; the form always sends one, but a line without a price
 *     is still accepted so an order queued offline by an older screen is never stuck. Absent or
 *     false is the old "write down what I ordered by phone".
 * @param emailRequested on a purchase order: also email it to the supplier now (the switch on the
 *     form). The order is made first either way; whether the email went is reported on the order.
 * @param orderedByPhone on a purchase order: already ordered by phone, so it is kept, numbered and
 *     receivable, but nothing is sent. Ignored with {@code emailRequested}.
 * @param deliveryCharge on a purchase order: what the supplier charges to bring it, for the whole
 *     order. Absent is "not said"; zero is free delivery.
 * @param approvalNote from someone who can only create orders: a note for whoever approves it.
 */
public record ExpectedDeliveryRequest(
        UUID vendorId,
        String expectedDate,
        @Size(max = 200) String reference,
        @Size(max = 500) String note,
        @NotEmpty(message = "Add at least one thing you are expecting.") @Valid List<Line> lines,
        // Multiple branches (V52): the branch it is going to. Absent means the company's only branch,
        // or the one branch the person works at.
        UUID branchId,
        Boolean purchaseOrder,
        Boolean emailRequested,
        // On a purchase order: the buyer already ordered it by phone, so nothing is sent and the
        // order is "with the supplier" from the start (redesign 2026-11-04).
        Boolean orderedByPhone,
        @DecimalMin(value = "0", message = "Delivery can't cost less than nothing.") BigDecimal deliveryCharge,
        @Size(max = 500) String approvalNote) {

    /** The shape before approval (V78). */
    public ExpectedDeliveryRequest(
            UUID vendorId, String expectedDate, String reference, String note, List<Line> lines, UUID branchId,
            Boolean purchaseOrder, Boolean emailRequested, Boolean orderedByPhone, BigDecimal deliveryCharge) {
        this(vendorId, expectedDate, reference, note, lines, branchId, purchaseOrder, emailRequested, orderedByPhone,
                deliveryCharge, null);
    }

    /** The shape before prices (2026-11-06). */
    public ExpectedDeliveryRequest(
            UUID vendorId, String expectedDate, String reference, String note, List<Line> lines, UUID branchId,
            Boolean purchaseOrder, Boolean emailRequested, Boolean orderedByPhone) {
        this(vendorId, expectedDate, reference, note, lines, branchId, purchaseOrder, emailRequested, orderedByPhone, null, null);
    }

    /** The shape before "ordered by phone". */
    public ExpectedDeliveryRequest(
            UUID vendorId, String expectedDate, String reference, String note, List<Line> lines, UUID branchId,
            Boolean purchaseOrder, Boolean emailRequested) {
        this(vendorId, expectedDate, reference, note, lines, branchId, purchaseOrder, emailRequested, null, null, null);
    }

    /** The shape before {@code branchId} (multiple branches, Phase 1). */
    public ExpectedDeliveryRequest(UUID vendorId, String expectedDate, String reference, String note, List<Line> lines) {
        this(vendorId, expectedDate, reference, note, lines, null, null, null, null, null, null);
    }

    /** The shape before purchase orders. */
    public ExpectedDeliveryRequest(
            UUID vendorId, String expectedDate, String reference, String note, List<Line> lines, UUID branchId) {
        this(vendorId, expectedDate, reference, note, lines, branchId, null, null, null, null, null);
    }

    /** The shape before the email switch. */
    public ExpectedDeliveryRequest(
            UUID vendorId, String expectedDate, String reference, String note, List<Line> lines, UUID branchId,
            Boolean purchaseOrder) {
        this(vendorId, expectedDate, reference, note, lines, branchId, purchaseOrder, null, null, null, null);
    }

    public boolean isEmailRequested() {
        return Boolean.TRUE.equals(emailRequested);
    }

    public boolean isOrderedByPhone() {
        return Boolean.TRUE.equals(orderedByPhone);
    }

    public boolean isPurchaseOrder() {
        return Boolean.TRUE.equals(purchaseOrder);
    }

    public record Line(
            @NotNull UUID productId,
            @NotNull String unit,
            @NotNull @DecimalMin(value = "0", inclusive = false, message = "How many did you order?")
                    BigDecimal quantity,
            // What one of {@code unit} costs - per bag, not per kg. Zero is allowed (a free sample).
            @DecimalMin(value = "0", message = "A price can't be less than nothing.") BigDecimal price) {
    }
}
