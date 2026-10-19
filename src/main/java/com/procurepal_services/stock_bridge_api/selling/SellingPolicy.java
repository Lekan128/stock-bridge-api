package com.procurepal_services.stock_bridge_api.selling;

import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.marketplace.SellerDirectory;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place that answers what a tenant's <b>selling price</b> means - SELLING_PRICE_PLAN.md
 * section 4.1.
 *
 * <h2>Why this exists</h2>
 * {@link Client#canSell()} is the MARKETPLACE rule: a vendor (or ProcurePal) lists its products, so
 * a selling price is required and is the price a buyer pays at checkout. A company that sells some
 * of its own stock over the counter needs the same field to be OPTIONAL. Before this class each
 * call site read {@code canSell()} for itself and decided both questions at once - "do we keep a
 * price?" and "must there be one?" - which was fine while the two always had the same answer and
 * would not be once they don't. Every price question now comes here, split into the questions
 * below, so the answers cannot drift apart between the product form, the sheets and the export.
 *
 * <h2>What stays on canSell()</h2>
 * Anything about LISTING - the storefront's seller set ({@link SellerDirectory}), moderation, the
 * vendor workspace guard, checkout. Those are about who may sell on the marketplace, not about what
 * a price on a product means, and they do not move here.
 *
 * <h2>The company switch</h2>
 * {@link Client#isSellsStock()} (V45) is what makes a company's selling price optional-but-present:
 * it {@link #showsSellingPrice shows} one without {@link #requiresSellingPrice requiring} one. It
 * changes what the screens show and never which stock writes are accepted - nothing in the stock
 * ledger asks this class anything.
 */
@Component
@RequiredArgsConstructor
public class SellingPolicy {

    private final SellerDirectory sellerDirectory;

    /**
     * Whether this tenant's products carry a selling price at all - the field on the product form,
     * the column on the sheets and the export, and a value kept rather than discarded when sent.
     */
    public boolean showsSellingPrice(Client client) {
        return client != null && (client.canSell() || client.isSellsStock());
    }

    /**
     * Whether every product must have one. Marketplace sellers only: a listing without a price has
     * nothing for a buyer to pay. A company that sells from its stock never has this - a product it
     * only uses itself has no price to give.
     */
    public boolean requiresSellingPrice(Client client) {
        return client != null && client.canSell();
    }

    /** Whether this tenant records counter sales from its stock - Record a sale, the Sales report. */
    public boolean recordsSales(Client client) {
        return client != null && client.isSellsStock();
    }

    /**
     * Whether this tenant may turn selling from stock on at all. Companies only in v1 (plan decision
     * 6): a vendor's every product already has a required price and its own marketplace sales.
     */
    public boolean canChooseToSellStock(Client client) {
        return client != null && !client.isVendor();
    }

    /** {@link #showsSellingPrice(Client)} for a tenant id; false for no tenant or an unknown one. */
    @Transactional(readOnly = true)
    public boolean showsSellingPrice(UUID tenantId) {
        return showsSellingPrice(ownerOf(tenantId));
    }

    /** {@link #requiresSellingPrice(Client)} for a tenant id; false for no tenant or an unknown one. */
    @Transactional(readOnly = true)
    public boolean requiresSellingPrice(UUID tenantId) {
        return requiresSellingPrice(ownerOf(tenantId));
    }

    private Client ownerOf(UUID tenantId) {
        return sellerDirectory.findSellerOfRecord(tenantId).orElse(null);
    }
}
