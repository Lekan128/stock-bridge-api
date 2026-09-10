package com.procurepal_services.stock_bridge_api.imports.dto;

import jakarta.validation.constraints.NotNull;

/** Body of {@code PATCH /api/imports/{id}/rows/{rowId}/skip} - {@code { "skipped": true|false }}. */
public record SkipRowRequest(@NotNull Boolean skipped) {
}
