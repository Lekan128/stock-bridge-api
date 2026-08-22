package com.procurepal_services.stock_bridge_api.marketplace.catalog.dto;

import com.procurepal_services.stock_bridge_api.entity.Client;
import java.util.UUID;

/**
 * Who is selling this, as the PUBLIC storefront exposes them.
 *
 * <h2>An allowlist, and the fields it deliberately omits</h2>
 * Name, slug, logo, whether they are the marketplace operator, and how many products
 * they have live. That is all.
 *
 * The {@code clients} row this is built from also carries
 * {@code admin_contact_email}, {@code phone}, {@code address_line1/2}, {@code city},
 * {@code state}, {@code payment_terms} and {@code commission_rate}. None of them is
 * here, and the omission is the point of the record existing at all rather than
 * serialising a Client:
 * <ul>
 *   <li><b>Contact details.</b> A vendor agreed to sell through this marketplace, not
 *       to publish their phone number and business address to anonymous visitors. If
 *       buyers could contact sellers directly the platform would be disintermediated
 *       on the second order, and - more plainly - nobody consented to it. Buyer/seller
 *       contact belongs on an order, between two parties who have actually
 *       transacted.</li>
 *   <li><b>commission_rate.</b> What the platform charges one vendor is commercially
 *       confidential from every other vendor, and rates differ per vendor by design
 *       (see Client.commissionRate). It has no business leaving the server on an
 *       unauthenticated endpoint.</li>
 *   <li><b>payment_terms.</b> A fact about that client as a BUYER. Meaningless here
 *       and confusing next to a seller's name.</li>
 * </ul>
 * Built by hand rather than by reusing an existing client DTO for the same reason
 * {@link MarketplaceProductResponse} is built by hand rather than from
 * ProductResponse: an allowlist stays safe when somebody adds a column, and a
 * denylist does not.
 *
 * <h2>platformOwner is published, and that is intentional</h2>
 * It is not a secret - the storefront has always been visibly ProcurePal's - and the
 * frontend uses it to badge ProcurePal's own listings as "Sold by ProcurePal" rather
 * than rendering the operator as though it were one vendor among the rest. It confers
 * no capability on the reader.
 *
 * <p>Field names mirror {@code stock-bridge-ui/src/features/storefront/types.ts}.
 */
public record MarketplaceSellerResponse(
        UUID id,
        String name,
        String slug,
        String logoUrl,
        boolean platformOwner,
        /**
         * Live listings under this seller. Only populated on the seller-directory and
         * storefront-header responses, where it drives "42 products"; it is 0 on the
         * copy embedded in a product tile, which never shows it and would otherwise
         * cost one COUNT per row.
         */
        long productCount) {

    /** With a live-listing count, for the seller directory and the storefront header. */
    public static MarketplaceSellerResponse from(Client seller, long productCount) {
        return new MarketplaceSellerResponse(
                seller.getId(),
                seller.getName(),
                seller.getSlug(),
                seller.getLogoUrl(),
                seller.isPlatformOwner(),
                productCount);
    }

    /**
     * The embedded form carried on every product tile and detail page.
     *
     * <p>Null-tolerant, and that is not defensiveness for its own sake: a product read
     * through the {@code ?ids=} cart-hydration path can outlive its seller's active
     * status within the same session, and an order line renders long after a vendor has
     * been delisted. A missing seller renders as an unattributed product rather than
     * failing the whole page - the buyer's cart must not become unopenable because a
     * vendor was suspended this morning.
     */
    public static MarketplaceSellerResponse of(Client seller) {
        return seller == null ? null : from(seller, 0L);
    }
}
