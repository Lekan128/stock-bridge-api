package com.procurepal_services.stock_bridge_api.product.unit;

/**
 * The axis {@link UnitOfMeasureCategory} does not capture: what a code is FOR, not what kind
 * of quantity it measures.
 *
 * <h2>The ambiguity this exists to remove</h2>
 * Before this split, a product had one {@code unitOfMeasure} slot plus a numeric count, and
 * that slot had to serve two unrelated questions at once - "what is it measured in" (kg,
 * litre, a bare piece) and "how is it packaged/sold" (a bag, a carton, a box). "A 50kg bag"
 * needs BOTH answers together: it IS packaged as a bag, AND each bag holds 50 of the
 * measurement unit kg. A single flat list can express one or the other but never both at
 * once - {@code unitOfMeasure=BAG, unitCount=50} reads as "50 of them, no unit", while
 * {@code unitOfMeasure=KG, unitCount=50} reads as "50 kg, no bag". Neither says what the
 * business actually means.
 *
 * <h2>The fix, modelled on Odoo</h2>
 * A product now carries two independent codes - {@code unitOfMeasure} (this role's
 * {@link #BASE}) and {@code packagingUnit} (this role's {@link #PACKAGING}) - plus
 * {@code packagingSize} saying how many of the base unit one packaging unit holds. "A 50kg
 * bag" becomes {@code unitOfMeasure=KG, packagingUnit=BAG, packagingSize=50}: unambiguous,
 * and it also covers goods sold loose with no packaging at all
 * ({@code unitOfMeasure=LITER}, both packaging fields null).
 *
 * <h2>Why this lives on {@link UnitOfMeasure} rather than splitting the enum in two</h2>
 * BASE and PACKAGING share everything else - a stable {@code code}, a display
 * {@code label}, a {@link UnitOfMeasureCategory} for picker grouping - and every consumer
 * that already resolves a code through {@link UnitOfMeasure#fromCode(String)} keeps working
 * unchanged; only the caller that cares which role a code plays (product validation) needs
 * to ask. Two enums would duplicate the lookup machinery for a distinction that is really
 * just one more field.
 *
 * <h2>Why the constraint is enforced at the call site, not inside {@code fromCode}</h2>
 * {@code fromCode} answers "is this a code on the list at all" and stays role-agnostic on
 * purpose: it is shared by two validation call sites in {@code ProductManagementService}
 * that each want a DIFFERENT role, {@code resolveUnitOfMeasure} (requires {@link #BASE}) and
 * {@code resolvePackagingUnit} (requires {@link #PACKAGING}). Baking one role assumption into
 * the shared lookup would make it wrong for whichever caller did not ask for that role.
 */
public enum UnitOfMeasureRole {
    /**
     * What a product is fundamentally measured in - a weight, a volume, a length, or (for
     * uncounted discrete goods) the generic {@link UnitOfMeasure#PIECE}. This is the unit
     * {@code packagingSize} is a quantity OF; a product may have this set with no packaging
     * at all (sold loose, e.g. {@code unitOfMeasure=LITER}).
     */
    BASE,

    /**
     * How a product is grouped or sold as a container - a Bag, Carton, Box and similar. Never
     * meaningful on its own: a {@code packagingUnit} always pairs with a {@code packagingSize}
     * (how many of the BASE unit it holds) and, transitively, with the {@code unitOfMeasure}
     * that size is counting - see {@code PackagingRequiresUnitOfMeasureException}.
     */
    PACKAGING
}
