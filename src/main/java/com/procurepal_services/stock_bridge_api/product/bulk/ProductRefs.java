package com.procurepal_services.stock_bridge_api.product.bulk;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * The stock sheet's hidden {@code Ref} column - how a row is matched back to its product
 * (BULK_IMPORT_CX_PLAN.md task 1.4).
 *
 * <h2>Why not the SKU</h2>
 * The SKU used to be the key, and it is a poor one for this sheet: most are generated codes nobody
 * reads, some were damaged by older parsers ({@code 28.0}), and a person tidying the sheet can
 * change one without knowing it matters. The product name is worse - three products can be called
 * Carrot. So the row carries a machine key in a column nobody needs to look at, and the name and
 * code beside it are there for people.
 *
 * <p>It is the product id, 22 url-safe characters rather than 36 with dashes, so it does not read
 * as an id to anyone who unhides the column. It is not a secret: a ref from another company's
 * sheet simply matches nothing in this one, because every lookup is scoped to the tenant.
 */
public final class ProductRefs {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private ProductRefs() {
    }

    public static String encode(UUID productId) {
        ByteBuffer bytes = ByteBuffer.allocate(16);
        bytes.putLong(productId.getMostSignificantBits());
        bytes.putLong(productId.getLeastSignificantBits());
        return ENCODER.encodeToString(bytes.array());
    }

    /** Empty for anything that is not a ref this class wrote - a typo, a pasted value, a blank. */
    public static Optional<UUID> decode(String ref) {
        if (ref == null) {
            return Optional.empty();
        }
        String trimmed = ref.trim();
        if (trimmed.length() != 22) {
            return Optional.empty();
        }
        try {
            ByteBuffer bytes = ByteBuffer.wrap(DECODER.decode(trimmed));
            return Optional.of(new UUID(bytes.getLong(), bytes.getLong()));
        } catch (IllegalArgumentException notARef) {
            return Optional.empty();
        }
    }
}
