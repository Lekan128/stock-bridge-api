package com.procurepal_services.stock_bridge_api.imports.dto;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * Body of {@code PATCH /api/imports/{id}/rows/{rowId}} - {@code { "normalized": { … } }}.
 *
 * <p>A partial map: only the cells that changed. Sending the whole row would make two people
 * editing different cells of the same row clobber each other, and would make an unchanged cell
 * indistinguishable from one deliberately re-set to its current value.
 *
 * <p>A key present with a null value means "the user emptied this cell", which is not the same
 * as the key being absent, and the engine keeps the two apart - see {@code ImportFields.EDITED}
 * for why that distinction has to survive into the next validation pass.
 */
public record PatchRowRequest(@NotNull Map<String, Object> normalized) {
}
