package com.procurepal_services.stock_bridge_api.product.unit.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /api/products/unit-of-measure-requests} - the
 * "can't find your unit? tell us" box beside the fixed, curated list on
 * {@code ProductController.unitsOfMeasure}. See
 * {@link com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasure}'s
 * class doc, section "Requests for units not on this list": that enum is
 * deliberately closed, and this is the separate workflow it defers to rather
 * than accepting free text itself.
 *
 * <h2>Two fields, on purpose</h2>
 * This is a nudge to a human at support@procurepaddy.com, not a form that lets
 * a tenant add their own unit to the product catalog - that would recreate
 * exactly the fragmented, uncurated list Amazon/Jumia/Odoo-style pickers
 * avoid, which is the whole reason {@code UnitOfMeasure} is a fixed enum
 * rather than free text in the first place. {@code requestedUnit} is what
 * they typed ("50L Jerry Can"); {@code note} is why, and is optional because
 * the unit name alone is often self-explanatory.
 *
 * <p>There is no table behind this request, only an email, but the length
 * limits still matter: a caller who can reach this endpoint can otherwise
 * write an arbitrarily long string into a message a human is expected to
 * read, and {@code EmailLayout.escape} only stops it being rendered as
 * markup, not being unreasonably long.
 */
public record AdditionalUnitOfMeasureRequest(
        @NotBlank @Size(max = 200) String requestedUnit, @Size(max = 1000) String note) {
}
