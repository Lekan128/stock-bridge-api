package com.procurepal_services.stock_bridge_api.product;

/**
 * A product's pack names the same unit as its stock unit - "a Piece of 34 Pieces".
 *
 * <h2>Why this class exists at all</h2>
 * Until COUNT units were allowed to serve either role, this state was unreachable: a code was
 * either BASE or PACKAGING, so {@code unitOfMeasure} and {@code packagingUnit} could never be the
 * same constant. The enum was enforcing an invariant nobody had written down.
 *
 * <p>Relaxing the roles (see {@code UnitOfMeasure.canServeAs}) is right - "piece" really is the
 * stock unit of a phone and the pack of a turmeric sold in 34 g pieces - but it takes that
 * accidental guarantee away with it. So the invariant moves here, where it is visible and
 * testable, instead of being a side effect of how the enum happened to be split.
 *
 * <h2>Why it is an error and not a silent correction</h2>
 * A pack of itself has no conversion: {@code packagingSize} would be "how many pieces in a
 * piece", and any answer other than 1 is a contradiction while 1 is a pack that does nothing.
 * There is no reading of the row that we could pick on the user's behalf, so we ask.
 *
 * <p>Maps to 400.
 */
public class PackagingUnitSameAsStockUnitException extends RuntimeException {

    public PackagingUnitSameAsStockUnitException(String unitLabel) {
        super("A pack has to be something different from the stock unit — this product is counted in "
                + unitLabel + " and packed in " + unitLabel + ". Either leave the pack columns empty, "
                + "or name the container the " + unitLabel + " come in.");
    }
}
