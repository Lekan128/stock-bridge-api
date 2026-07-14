package com.procurepal_services.stock_bridge_api.analytics.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record TopProductEntry(UUID productId, String name, String sku, BigDecimal value, long quantity) {
}
