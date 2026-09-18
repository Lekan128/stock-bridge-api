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
        @NotEmpty @Valid List<Line> lines) {

    /**
     * @param unit the line's {@code unit} from {@link DeliveryLineResponse}.
     * @param price the price of ONE of {@code unit}; absent means the last price paid.
     */
    public record Line(
            @NotNull UUID productId,
            String unit,
            @NotNull @Positive BigDecimal quantity,
            @DecimalMin(value = "0", inclusive = true) BigDecimal price) {
    }
}
