package com.procurepal_services.stock_bridge_api.branch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/** {@code POST /api/branches/{id}/areas}: one or more new areas, in the order they were added. */
public record AreaRequest(@NotEmpty @Size(max = 20) List<@Valid NewArea> areas) {

    /** @param keepsStock absent means it keeps stock - the answer the setup suggests when unsure. */
    public record NewArea(@Size(max = 80) String name, Boolean keepsStock) {
    }
}
