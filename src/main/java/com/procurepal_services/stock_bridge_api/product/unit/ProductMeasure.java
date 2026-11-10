package com.procurepal_services.stock_bridge_api.product.unit;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;

/**
 * One of a product's own units (UNITS_PLAN.md Part 1): a derica, a cup, a mudu - "1 Derica = 0.6 kg" of this rice.
 *
 * <p>Unlike a pack, which can differ by supplier and by delivery (a 50 kg bag from one mill, 25 kg from another), a
 * measure is the kitchen's own and is the same every time, so it is set once on the product. It joins the product's
 * unit set ({@link UnitOptions}) beside the stock unit and the packs: every quantity box offers it, and "5 dericas" is
 * recorded as 3 kg with "5 Derica" kept as typed.
 *
 * @param name what the kitchen calls it, singular: "Derica", "Small bowl". Its code on the wire and in
 *     {@code entered_unit}, so it never collides with a unit code ({@link ProductMeasures#validate}).
 * @param size how many of the product's stock unit one holds. Strictly positive, at most 3 places.
 * @param hidden no longer offered for new entries (Part 3): it was used, so it stays to read the past by.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProductMeasure(String name, BigDecimal size, Boolean hidden) {

    public ProductMeasure(String name, BigDecimal size) {
        this(name, size, null);
    }

    /** Hidden is left out of the JSON when it isn't so: absent and false are the same. Not a bean getter on purpose. */
    public boolean hiddenNow() {
        return Boolean.TRUE.equals(hidden);
    }

    public ProductMeasure hide() {
        return new ProductMeasure(name, size, true);
    }
}
