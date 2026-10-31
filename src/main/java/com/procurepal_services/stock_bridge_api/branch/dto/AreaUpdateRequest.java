package com.procurepal_services.stock_bridge_api.branch.dto;

import jakarta.validation.constraints.Size;

/**
 * {@code PATCH /api/areas/{id}}: absent fields are left as they are.
 *
 * @param delivery true makes this the area deliveries arrive in (false is not a request: a branch always
 *     has one, so another area is made the delivery area instead).
 */
public record AreaUpdateRequest(@Size(max = 80) String name, Boolean keepsStock, Boolean delivery, Boolean shortIsUsed) {

    /** The shape before {@code shortIsUsed} (Part 3.5). */
    public AreaUpdateRequest(String name, Boolean keepsStock, Boolean delivery) {
        this(name, keepsStock, delivery, null);
    }
}
