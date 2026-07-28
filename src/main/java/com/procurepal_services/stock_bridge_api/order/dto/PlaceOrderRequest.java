package com.procurepal_services.stock_bridge_api.order.dto;

import com.procurepal_services.stock_bridge_api.address.dto.DeliveryAddressRequest;
import com.procurepal_services.stock_bridge_api.entity.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Note what is NOT here: no line items, no prices, no totals. The order is built
 * from the server-side cart and priced from the live catalog, so a tampered request
 * body cannot buy a generator for ₦1. The only things the buyer gets to choose are
 * how they pay, where it goes and what to tell the driver.
 *
 * Exactly one of deliveryAddressId / newAddress must be supplied. saveAddress keeps
 * an inline-typed address in the address book afterwards; without it the address is
 * still snapshotted onto the order (it has to be - that is where it ships) but leaves
 * no reusable row.
 */
public record PlaceOrderRequest(
        @NotNull PaymentMethod paymentMethod,
        UUID deliveryAddressId,
        @Valid DeliveryAddressRequest newAddress,
        Boolean saveAddress,
        @Size(max = 1000) String customerNote) {
}
