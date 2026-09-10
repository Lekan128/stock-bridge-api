package com.procurepal_services.stock_bridge_api.marketplace;

import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads a SELLER's catalog from inside a BUYER's request.
 *
 * <h2>The bug this class exists to prevent</h2>
 * {@code cart_items.product_id} and {@code order_items.product_id} point at some
 * seller's products, while the request runs under the buyer's Hibernate tenant
 * filter. Any JPQL/criteria query against Product therefore gains
 * {@code AND client_id = <buyer>} and matches nothing - the failure mode is not an
 * error but a silently empty cart, which is exactly the kind of bug that ships.
 *
 * The one load that is NOT filtered is a load by primary key: Hibernate applies
 * {@code @Filter} to queries, not to {@code EntityManager.find}. Spring Data's
 * {@code findById} is a straight {@code em.find}, so it crosses the tenant boundary
 * on purpose here - and every result is then re-checked in Java against the set of
 * ACTIVE SELLERS, so a buyer can never smuggle one of their own product ids (or
 * another tenant's) into a cart line.
 *
 * <h2>What changed when selling opened up to vendors</h2>
 * The re-check used to compare against a single id, the platform owner's. It now
 * asks {@link SellerDirectory} whether the product's owner may sell at all. That is
 * a widening of an allowlist, not its removal: a product belonging to an ordinary
 * buying company, or to a vendor whose account has been deactivated, still fails the
 * check and is still unbuyable. Replacing the check with "any product id is fine
 * because it was loaded by id" would let one tenant add another tenant's private
 * stock to their cart by guessing a UUID.
 *
 * Note {@code findAllById} is deliberately NOT used: Spring Data implements it as a
 * {@code WHERE id IN (:ids)} query, which the tenant filter does reach. Loading one
 * id at a time is the correct trade for a cart of a dozen lines, and repeat ids hit
 * the persistence context rather than the database.
 */
@Component
@RequiredArgsConstructor
public class BuyerCatalogLookup {

    private final ProductRepository productRepository;
    private final SellerDirectory sellerDirectory;

    /**
     * The catalog product with this id, or empty if it does not exist, belongs to
     * someone who may not sell, or has since been unlisted, deactivated or had its
     * approval withdrawn. All of them collapse into "not purchasable" for the caller,
     * which is the only distinction a buyer is entitled to.
     */
    @Transactional(readOnly = true)
    public Optional<Product> findPurchasable(UUID productId) {
        return findAnyCatalogProduct(productId).filter(BuyerCatalogLookup::isPurchasable);
    }

    /**
     * As {@link #findPurchasable} but without the listed/active predicate: an order
     * already placed must still render its lines (and reorder must be able to say
     * "this is no longer available") after ProcurePal unlists a product.
     */
    @Transactional(readOnly = true)
    public Optional<Product> findAnyCatalogProduct(UUID productId) {
        if (productId == null) {
            return Optional.empty();
        }
        Set<UUID> sellerIds = sellerDirectory.activeSellerIds();
        if (sellerIds.isEmpty()) {
            return Optional.empty();
        }
        return productRepository.findById(productId).filter(product -> sellerIds.contains(product.getClientId()));
    }

    /** Bulk variant preserving the caller's iteration order; missing ids are simply absent. */
    @Transactional(readOnly = true)
    public Map<UUID, Product> findAllByIds(Collection<UUID> productIds) {
        Map<UUID, Product> byId = new LinkedHashMap<>();
        for (UUID productId : productIds) {
            findAnyCatalogProduct(productId).ifPresent(product -> byId.put(product.getId(), product));
        }
        return byId;
    }

    /**
     * Listed, active AND approved - i.e. something this marketplace sells at all.
     *
     * <p>The approval clause is not decoration. A seller controls
     * {@code is_marketplace_listed} on their own rows, so without it a vendor could
     * push an unmoderated - or a previously REJECTED - product into a buyer's cart by
     * flipping their own flag, which is exactly what moderation exists to prevent.
     * This method and
     * {@code MarketplaceProductSpecifications.listedBy} must agree on what "public"
     * means; they are the SQL and the Java statement of one rule, and a product that
     * passed one but not the other would be visible in the catalog and unbuyable, or
     * invisible and buyable.
     *
     * Deliberately says nothing about quantity. Availability is not a property of the
     * product row: a seller's quantity_on_hand only falls at dispatch, so what is
     * actually left to sell is that number minus everything already sold and awaiting a
     * van. That calculation lives in CatalogStockService, and every stock question goes
     * there rather than reading the column directly.
     */
    public static boolean isPurchasable(Product product) {
        return product.isMarketplaceListed()
                && product.isActive()
                && product.getApprovalStatus() != null
                && product.getApprovalStatus().isListable();
    }
}
