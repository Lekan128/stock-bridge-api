package com.procurepal_services.stock_bridge_api.product.bulk;

import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import java.util.List;

public record BulkUploadResponse(int createdCount, List<ProductResponse> products) {
}
