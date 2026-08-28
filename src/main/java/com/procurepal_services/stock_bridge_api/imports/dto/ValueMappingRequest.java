package com.procurepal_services.stock_bridge_api.imports.dto;

import com.procurepal_services.stock_bridge_api.imports.ValueResolution;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code PATCH /api/imports/{id}/value-mappings} - the bare {@code {column, from, to}}
 * BULK_IMPORT_CONTRACT.md section 3 specifies, not wrapped in anything.
 *
 * <p>One request settles every row carrying {@code from} in {@code column}. That is the whole
 * design of section 6.4 expressed as a single HTTP call, and it is why the response is the
 * re-validated session rather than a list of touched rows: the counters, the unresolved list and
 * the Continue button all move at once, and the review screen re-renders from one object.
 */
public record ValueMappingRequest(
        @NotBlank String column, @NotBlank String from, @NotNull ValueResolution to) {
}
