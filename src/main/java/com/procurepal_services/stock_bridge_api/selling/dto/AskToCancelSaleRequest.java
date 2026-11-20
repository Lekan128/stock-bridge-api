package com.procurepal_services.stock_bridge_api.selling.dto;

import com.procurepal_services.stock_bridge_api.selling.SaleCancelReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A cashier asks for their sale to be cancelled, past its first two minutes (selling till Step 3). */
public record AskToCancelSaleRequest(@NotNull SaleCancelReason reason, @Size(max = 300) String note) {
}
