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
 * @param expectedDate when the supplier said it would come. Optional; plenty never say.
 */
public record ExpectedDeliveryRequest(
        UUID vendorId,
        String expectedDate,
        @Size(max = 200) String reference,
        @Size(max = 500) String note,
        @NotEmpty(message = "Add at least one thing you are expecting.") @Valid List<Line> lines) {

    public record Line(
            @NotNull UUID productId,
            @NotNull String unit,
            @NotNull @DecimalMin(value = "0", inclusive = false, message = "How many did you order?")
                    BigDecimal quantity,
            BigDecimal price) {
    }
}
