package com.procurepal_services.stock_bridge_api.marketplace.catalog.dto;

import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.entity.ProductCategory;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A product as the PUBLIC catalog exposes it. Field-for-field the frontend's
 * {@code MarketplaceProduct} (src/features/storefront/types.ts), which CartContext and
 * the storefront header already import.
 *
 * What is deliberately absent matters more than what is here: no costPrice, no
 * lowStockThreshold, no incomingQuantity, no clientId. This record is served
 * unauthenticated to anyone on the internet, so it is an allowlist of things
 * ProcurePal is happy to publish, built by hand rather than by reusing ProductResponse -
 * which carries cost price and would have leaked ProcurePal's margins the first time
 * someone added a field to it.
 *
 * quantityOnHand IS published, alongside the derived {@code inStock}, because the
 * storefront has to show "12 left" style urgency and the buyer needs to know a 40-crate
 * order cannot ship. If ProcurePal later decides exact stock is commercially sensitive,
 * drop the number and keep the boolean - the frontend already branches on inStock.
 *
 * <h2>quantityOnHand carries SELLABLE stock, not the raw column</h2>
 * It is {@code products.quantity_on_hand} minus everything already sold and awaiting
 * dispatch - {@code order/CatalogStockService.availableToSell}. Stock only leaves
 * quantity_on_hand at OUT_FOR_DELIVERY, so the raw column still counts twelve bags that
 * are paid for and sitting on a pallet with someone else's name on them. Publishing that
 * number let a tile advertise "12 in stock" for goods nobody could buy; the order
 * endpoint refused the oversell, but not until the buyer had already built a cart.
 *
 * The FIELD NAME stays {@code quantityOnHand} deliberately: the frontend's
 * MarketplaceProduct and the cart's CartItem both read it, and renaming it would be a
 * breaking change to fix wording. What the storefront means by the number has always been
 * "how many can I buy", which is now what it actually is. The admin DTO is where the raw
 * warehouse count still lives - see AdminCatalogProductResponse.
 */
public record MarketplaceProductResponse(
        UUID id,
        String name,
        String sku,
        String slug,
        String description,
        String brand,
        BigDecimal unitPrice,
        String imageUrl,
        String unitOfMeasure,
        int minOrderQuantity,
        int quantityOnHand,
        boolean inStock,
        UUID categoryId,
        String categoryName,
        /**
         * Who sells it - name and logo only; see {@link MarketplaceSellerResponse} for
         * the fields deliberately withheld. Null only if the seller row vanished
         * between the product query and this projection, which renders as an
         * unattributed tile rather than failing the page.
         */
        MarketplaceSellerResponse seller) {

    /**
     * @param availableToSell sellable units, from CatalogStockService's batch lookup.
     *     There is no single-argument overload on purpose: the only way to build this
     *     record is to have gone and asked, so a new call site cannot quietly fall back to
     *     the raw column and reintroduce the phantom-stock bug.
     * @param seller resolved from SellerDirectory's batch map. Required for the same
     *     reason: with several sellers on one grid, "who sells this" is a question every
     *     tile has to answer, and a convenience overload that omitted it would produce
     *     unattributed listings on whichever surface forgot.
     */
    public static MarketplaceProductResponse from(
            Product product, int availableToSell, MarketplaceSellerResponse seller) {
        ProductCategory category = product.getCategory();
        return new MarketplaceProductResponse(
                product.getId(),
                product.getName(),
                product.getSku(),
                product.getSlug(),
                product.getDescription(),
                product.getBrand(),
                product.getUnitPrice(),
                product.getImageUrl(),
                product.getUnitOfMeasure(),
                product.getMinOrderQuantity(),
                availableToSell,
                // Stock is client-wide in this pass (contract §4.2: no per-branch
                // stock yet), so "in stock" is simply "there is at least one left to
                // sell". Deliberately NOT availableToSell >= minOrderQuantity: a buyer
                // who cannot meet the MOQ should be told that at the quantity stepper,
                // not have the product vanish from the grid.
                availableToSell > 0,
                category == null ? null : category.getId(),
                category == null ? null : category.getName(),
                seller);
    }
}
