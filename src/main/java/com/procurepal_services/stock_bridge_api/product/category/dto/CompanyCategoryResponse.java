package com.procurepal_services.stock_bridge_api.product.category.dto;

import java.util.UUID;

/** @param productCount active products in the category. */
public record CompanyCategoryResponse(UUID id, String name, long productCount) {
}
