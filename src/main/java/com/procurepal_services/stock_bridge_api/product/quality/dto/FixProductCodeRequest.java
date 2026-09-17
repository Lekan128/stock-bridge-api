package com.procurepal_services.stock_bridge_api.product.quality.dto;

import jakarta.validation.constraints.Size;

/** @param code the new code, or blank to use the suggestion (or a generated one). */
public record FixProductCodeRequest(@Size(max = 100) String code) {
}
