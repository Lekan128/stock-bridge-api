package com.procurepal_services.stock_bridge_api.product.category.dto;

import com.procurepal_services.stock_bridge_api.entity.CompanyCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CompanyCategoryRequest(@NotBlank @Size(max = CompanyCategory.NAME_MAX_LENGTH) String name) {
}
