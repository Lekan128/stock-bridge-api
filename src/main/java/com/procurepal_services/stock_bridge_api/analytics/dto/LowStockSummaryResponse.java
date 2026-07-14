package com.procurepal_services.stock_bridge_api.analytics.dto;

import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import java.util.List;

public record LowStockSummaryResponse(long count, List<ProductResponse> products) {
}
