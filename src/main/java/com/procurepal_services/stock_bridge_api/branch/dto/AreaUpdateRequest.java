package com.procurepal_services.stock_bridge_api.branch.dto;

import jakarta.validation.constraints.Size;

/**
 * {@code PATCH /api/areas/{id}}: absent fields are left as they are.
 *
 * @param confirms moves to it wait until someone there confirms them (Part 4); switching it off leaves the
 *     moves already waiting waiting.
 * @param sells whether things are sold from it (V103): Record a sale and table bills take stock only from areas
 *     that sell. A branch keeps at least one.
 * @param delivery true makes this the area deliveries arrive in (false is not a request: a branch always
 *     has one, so another area is made the delivery area instead).
 */
public record AreaUpdateRequest(
        @Size(max = 80) String name, Boolean keepsStock, Boolean delivery, Boolean shortIsUsed, Boolean confirms,
        Boolean sells) {
    public AreaUpdateRequest(String name, Boolean keepsStock, Boolean delivery, Boolean shortIsUsed, Boolean confirms) {
        this(name, keepsStock, delivery, shortIsUsed, confirms, null);
    }

    /** The shape before {@code shortIsUsed} (Part 3.5). */
    public AreaUpdateRequest(String name, Boolean keepsStock, Boolean delivery) {
        this(name, keepsStock, delivery, null, null, null);
    }

    /** The shape before {@code confirms} (Part 4). */
    public AreaUpdateRequest(String name, Boolean keepsStock, Boolean delivery, Boolean shortIsUsed) {
        this(name, keepsStock, delivery, shortIsUsed, null, null);
    }
}
