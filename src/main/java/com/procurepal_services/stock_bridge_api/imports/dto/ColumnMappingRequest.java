package com.procurepal_services.stock_bridge_api.imports.dto;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * Body of {@code PATCH /api/imports/{id}/mapping} - {@code { "columnMapping": { header: field|null } }}.
 *
 * <p>Replaces the mapping wholesale rather than patching it, because the mapping screen shows
 * every column at once and a user re-pointing one dropdown may well have re-pointed another in
 * the same visit. Nulls are meaningful: they are how a column is explicitly un-mapped.
 */
public record ColumnMappingRequest(@NotNull Map<String, String> columnMapping) {
}
