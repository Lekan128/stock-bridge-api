package com.procurepal_services.stock_bridge_api.imports.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A delivery typed into the app (BULK_IMPORT_CX_PLAN.md task 2.2). The date, invoice and supplier
 * apply to every line, the same way the upload screen's do.
 *
 * @param deliveryDate ISO date; absent means today.
 * @param expectedDeliveryId the expectation this delivery is the arrival of, when the screen was
 *     opened from one (BULK_IMPORT_CX_PLAN.md task 3.1). Committing credits its lines; undoing
 *     takes that back. Absent for a delivery typed from scratch, which is most of them.
 */
public record DeliveryRequest(
        String deliveryDate,
        @Size(max = 200) String invoiceNo,
        UUID vendorId,
        UUID expectedDeliveryId,
        @NotEmpty @Valid List<Line> lines,
        // Multiple branches (V52): the branch it arrived at - see ImportSession.branchId.
        UUID branchId) {

    /** The shape before {@code branchId} (multiple branches, Phase 1). */
    public DeliveryRequest(
            String deliveryDate, String invoiceNo, UUID vendorId, UUID expectedDeliveryId, List<Line> lines) {
        this(deliveryDate, invoiceNo, vendorId, expectedDeliveryId, lines, null);
    }

    /**
     * @param unit the line's {@code unit} from {@link DeliveryLineResponse}.
     * @param quantity how many are taken in - what becomes stock. Zero only when all of what came was
     *     sent back.
     * @param price the price of ONE of {@code unit}; absent means the last price paid.
     * @param sentBack how many of what came went back with the driver (V79, plan section 11.6). Never
     *     stock, and still owed on the order. Absent or zero: none.
     * @param sentBackReason WRONG_ITEM, DAMAGED, EXPIRED, NOT_ORDERED, PRICE or OTHER; needed with {@code sentBack}.
     * @param sentBackNote what else to say about it (needed with OTHER).
     */
    public record Line(
            @NotNull UUID productId,
            String unit,
            @NotNull @jakarta.validation.constraints.PositiveOrZero BigDecimal quantity,
            @DecimalMin(value = "0", inclusive = true) BigDecimal price,
            @jakarta.validation.constraints.PositiveOrZero BigDecimal sentBack,
            @jakarta.validation.constraints.Pattern(regexp = "WRONG_ITEM|DAMAGED|EXPIRED|NOT_ORDERED|PRICE|OTHER")
                    String sentBackReason,
            @Size(max = 200) String sentBackNote) {

        /** The shape before sending back (V79). */
        public Line(UUID productId, String unit, BigDecimal quantity, BigDecimal price) {
            this(productId, unit, quantity, price, null, null, null);
        }

        public boolean sendsBack() {
            return sentBack != null && sentBack.signum() > 0;
        }
    }
}
