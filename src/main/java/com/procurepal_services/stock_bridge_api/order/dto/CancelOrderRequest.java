package com.procurepal_services.stock_bridge_api.order.dto;

import jakarta.validation.constraints.Size;

/** Reason is optional but stored: a cancellation with no explanation is the one ops can never learn from. */
public record CancelOrderRequest(@Size(max = 500) String reason) {
}
