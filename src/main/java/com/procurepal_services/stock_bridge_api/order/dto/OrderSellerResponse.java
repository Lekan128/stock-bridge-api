package com.procurepal_services.stock_bridge_api.order.dto;

import com.procurepal_services.stock_bridge_api.entity.Client;
import java.util.UUID;

/**
 * Who fulfilled an order, as its BUYER is allowed to see them.
 *
 * <h2>Why not reuse MarketplaceSellerResponse</h2>
 * The two answer questions that only look alike. That one describes a seller a
 * shopper is browsing and carries a live listing count; this one describes a
 * counterparty on a completed transaction, where a listing count is meaningless and
 * the seller may no longer be listing at all. Sharing a record would mean one of the
 * two surfaces always carrying a field that lies.
 *
 * <h2>Still name and logo only</h2>
 * Even though these two parties HAVE now transacted, the seller's email, phone and
 * address stay off this record. Buyer-seller contact on ProcurePaddy runs through the
 * platform - that is what the platform is for - and an order detail page is read by
 * anyone in the buying company with VIEW_ORDERS, not just whoever placed it. If direct
 * contact is ever wanted it should be a deliberate feature with the seller's consent,
 * not a side effect of a DTO gaining a field.
 *
 * <p>Built from the seller OF RECORD rather than from the active-seller list: an
 * invoice from last March must still name the vendor who sold it, even after that
 * vendor has been suspended or delisted.
 */
public record OrderSellerResponse(UUID id, String name, String slug, String logoUrl, boolean platformOwner) {

    /** Null-tolerant: a hard-deleted seller renders as an unattributed order, never a 500. */
    public static OrderSellerResponse from(Client seller) {
        return seller == null
                ? null
                : new OrderSellerResponse(
                        seller.getId(),
                        seller.getName(),
                        seller.getSlug(),
                        seller.getLogoUrl(),
                        seller.isPlatformOwner());
    }
}
