package com.procurepal_services.stock_bridge_api.marketplace.dto;

import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The note is optional but lands on the order_status_events row the buyer's tracking
 * timeline renders, so "delayed at Berger, out again tomorrow" reaches the customer
 * without anyone picking up the phone.
 */
public record AdvanceOrderStatusRequest(@NotNull OrderStatus status, @Size(max = 500) String note) {
}
