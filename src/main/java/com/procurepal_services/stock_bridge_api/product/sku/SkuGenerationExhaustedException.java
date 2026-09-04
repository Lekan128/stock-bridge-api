package com.procurepal_services.stock_bridge_api.product.sku;

/**
 * A SKU could not be generated from the tenant's pattern. Two distinct causes share this
 * exception because both have the same remedy - change the pattern configuration - and both are
 * 409s reported the same way {@code SkuTakenException} is:
 *
 * <ul>
 *   <li>{@link #collisionRetries}: a generated value kept colliding with an already-taken SKU for
 *       {@code SkuGenerationService.MAX_COLLISION_RETRIES} attempts in a row - almost always
 *       because SKUs matching this pattern were created manually (e.g. before auto-generation was
 *       turned on) and the counter has caught up to them.
 *   <li>{@link #sequenceOverflow}: the counter's value no longer fits in the pattern's {@code
 *       {SEQ:N}} digit count - e.g. the 10,000th product against a 4-digit sequence. Silently
 *       widening past N digits was rejected in favor of failing loudly, because {@code
 *       SkuPatternValidator}'s save-time length check promised callers N digits, not N-or-more.
 * </ul>
 */
public class SkuGenerationExhaustedException extends RuntimeException {

    private SkuGenerationExhaustedException(String message) {
        super(message);
    }

    public static SkuGenerationExhaustedException collisionRetries(int attempts) {
        return new SkuGenerationExhaustedException("Could not generate a unique SKU after " + attempts
                + " attempts - the pattern's sequence range may be colliding with manually-created SKUs. "
                + "Check the pattern in SKU settings.");
    }

    public static SkuGenerationExhaustedException sequenceOverflow(long sequenceValue, int digits) {
        return new SkuGenerationExhaustedException("The next SKU sequence value (" + sequenceValue
                + ") no longer fits in " + digits + " digits. Widen {SEQ:N} in the pattern, or reset the "
                + "sequence, in SKU settings.");
    }
}
