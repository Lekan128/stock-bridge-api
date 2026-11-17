package com.procurepal_services.stock_bridge_api.selling.dto;

import com.procurepal_services.stock_bridge_api.selling.SalePaymentMethod;
import jakarta.validation.constraints.NotNull;

/** "Change how they paid" (selling till Step 1). */
public record ChangeSalePaymentRequest(@NotNull SalePaymentMethod paymentMethod) {
}
