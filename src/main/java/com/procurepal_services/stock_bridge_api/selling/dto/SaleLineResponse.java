package com.procurepal_services.stock_bridge_api.selling.dto;

import com.procurepal_services.stock_bridge_api.entity.CostBasis;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One line of a sale, as it stands after any fix: {@code saleAmount} includes a price added later,
 * and a cancelled line says so rather than disappearing.
 *
 * @param quantity in the product's stock unit; {@code enteredQuantity}/{@code enteredUnit} say what
 *     was typed when that was another unit ("3 cartons").
 * @param saleAmount what it went for; absent while it has no price.
 * @param costAmount what the stock it used had cost; absent when unknown (see {@code costBasis}).
 * @param unitOfMeasure the product's stock unit code ("PIECE", "KG") - what {@code quantity} counts.
 */
public record SaleLineResponse(
        UUID movementId,
        UUID productId,
        String productName,
        int quantity,
        String enteredUnit,
        BigDecimal enteredQuantity,
        BigDecimal unitPriceAtTime,
        BigDecimal enteredUnitPrice,
        BigDecimal saleAmount,
        BigDecimal costAmount,
        CostBasis costBasis,
        boolean cancelled,
        OffsetDateTime cancelledAt,
        String cancelledByName,
        boolean priceAdded,
        OffsetDateTime occurredAt,
        String unitOfMeasure) {
}
