package com.procurepal_services.stock_bridge_api.order.dto;

import java.util.UUID;

/**
 * deliveryAddressId is optional: the quote falls back to the company's default
 * address so the checkout page can price the basket before the buyer has touched the
 * address picker. A POST (not a GET) because it is priced from the server-side cart
 * and the frontend must never be able to cache a total.
 */
public record CheckoutQuoteRequest(UUID deliveryAddressId) {
}
