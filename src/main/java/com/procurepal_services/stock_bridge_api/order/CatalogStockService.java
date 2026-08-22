package com.procurepal_services.stock_bridge_api.order;

import com.procurepal_services.stock_bridge_api.cart.InsufficientCatalogStockException;
import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.OrderItem;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.repository.OrderItemRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.stock.StockManagementService;
import com.procurepal_services.stock_bridge_api.stock.dto.StockOutRequest;
import com.procurepal_services.stock_bridge_api.tenant.TenantScopeExecutor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The SELLER's side of stock: ProcurePal's own inventory, which is a real warehouse
 * with real quantities, not a price list. {@link IncomingStockService} is the mirror
 * image of this for the buyer.
 *
 * <h2>When stock leaves, and why it is dispatch and not order</h2>
 * The OUT movement is written on the transition into OUT_FOR_DELIVERY. That is the
 * moment the goods physically leave the warehouse, it is a single unambiguous
 * transition rather than a state that can be reached twice, and it is what makes
 * cancellation free: {@link com.procurepal_services.stock_bridge_api.entity.OrderStatus}
 * forbids CANCELLED from OUT_FOR_DELIVERY onwards, so an order can only ever be
 * cancelled while nothing has moved - there is no reversal path to write, and none is
 * needed. (Deducting at order time would have been the other option, and would have
 * required exactly that reversal, plus a second one for every abandoned Monnify
 * checkout.)
 *
 * <h2>Overselling, and the gap that dispatch-time deduction leaves</h2>
 * If stock only fell at dispatch, two companies could each buy the last ten bags on
 * the same afternoon and both would be told yes. So availability is not
 * {@code quantity_on_hand} - it is {@code quantity_on_hand} minus everything already
 * sold and not yet dispatched ({@link #committedQuantity}). Order creation re-checks
 * that figure with the catalog row LOCKED, so two concurrent checkouts of the last
 * ten bags serialise and the second one is refused.
 *
 * <h2>Why the committed-quantity query is native SQL</h2>
 * It joins order_items to orders, and Order is a {@code TenantAwareEntity}. In JPQL
 * that join is subject to the Hibernate tenant filter, so under the BUYER's filter it
 * would only count that buyer's own commitments - undercounting exactly the other
 * companies whose orders are the reason the check exists. Native SQL is not filtered,
 * which here is the correct semantics rather than a loophole: the number being
 * computed is a property of ProcurePal's warehouse, not of any one tenant.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CatalogStockService {

    /**
     * Sold, not yet dispatched. PENDING_PAYMENT is excluded - an unpaid checkout is
     * not a commitment, and reserving stock for one would let anyone empty the
     * warehouse by filling carts. Everything from OUT_FOR_DELIVERY onwards is excluded
     * because it has already been deducted from quantity_on_hand.
     */
    private static final String COMMITTED_QUANTITY_SQL =
            "SELECT COALESCE(SUM(oi.quantity), 0) FROM order_items oi "
                    + "JOIN orders o ON o.id = oi.order_id "
                    + "WHERE oi.product_id = :productId "
                    + "AND o.status IN ('PLACED', 'CONFIRMED', 'PROCESSING')";

    private final StockManagementService stockManagementService;
    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;
    private final TenantScopeExecutor tenantScopeExecutor;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * The same commitment rule as {@link #COMMITTED_QUANTITY_SQL}, for a whole page of
     * products in one round trip, expressed as what is left to sell.
     *
     * This exists because the PUBLIC catalog projects availability onto every tile it
     * renders: per-product it would be one query per row, on the one endpoint in the
     * application that anonymous traffic hits hardest. The LEFT JOIN is what lets a
     * product with no commitments at all still come back (an INNER JOIN would silently
     * drop it and the caller would read "missing" as zero available).
     *
     * GREATEST(...,0) mirrors the single-product floor: an oversold warehouse is at zero
     * available, never negative.
     */
    private static final String AVAILABLE_TO_SELL_SQL =
            "SELECT p.id, GREATEST(0, p.quantity_on_hand - COALESCE(c.committed, 0)) "
                    + "FROM products p "
                    + "LEFT JOIN (SELECT oi.product_id AS product_id, SUM(oi.quantity) AS committed "
                    + "           FROM order_items oi "
                    + "           JOIN orders o ON o.id = oi.order_id "
                    + "           WHERE o.status IN ('PLACED', 'CONFIRMED', 'PROCESSING') "
                    + "           GROUP BY oi.product_id) c ON c.product_id = p.id "
                    + "WHERE p.id IN (:productIds)";

    /** Batch form of {@link #COMMITTED_QUANTITY_SQL} - what the marketplace-admin table shows as "spoken for". */
    private static final String COMMITTED_QUANTITIES_SQL =
            "SELECT oi.product_id, SUM(oi.quantity) FROM order_items oi "
                    + "JOIN orders o ON o.id = oi.order_id "
                    + "WHERE oi.product_id IN (:productIds) "
                    + "AND o.status IN ('PLACED', 'CONFIRMED', 'PROCESSING') "
                    + "GROUP BY oi.product_id";

    /**
     * Catalog products of one seller with nothing left to sell once commitments are
     * counted, even though quantity_on_hand still reads positive.
     *
     * The public catalog's {@code inStockOnly} filter needs this as an id set rather than
     * a post-filter on the result page: dropping rows after the query would leave
     * totalElements and the page contents disagreeing, so page 3 of a filtered grid would
     * be short for no visible reason.
     */
    private static final String FULLY_COMMITTED_SQL =
            "SELECT p.id FROM products p "
                    + "JOIN (SELECT oi.product_id AS product_id, SUM(oi.quantity) AS committed "
                    + "      FROM order_items oi "
                    + "      JOIN orders o ON o.id = oi.order_id "
                    + "      WHERE o.status IN ('PLACED', 'CONFIRMED', 'PROCESSING') "
                    + "      GROUP BY oi.product_id) c ON c.product_id = p.id "
                    + "WHERE p.client_id IN (:sellerIds) AND p.quantity_on_hand - c.committed <= 0";

    /** Units of this catalog product owed to orders that have not left the warehouse yet. */
    @Transactional(readOnly = true)
    public int committedQuantity(UUID catalogProductId) {
        Number committed = (Number) entityManager
                .createNativeQuery(COMMITTED_QUANTITY_SQL)
                .setParameter("productId", catalogProductId)
                .getSingleResult();
        return committed == null ? 0 : committed.intValue();
    }

    /**
     * What a buyer may actually add to a cart today. Floored at zero: a warehouse that
     * has oversold is at zero available, not at a negative number that would read as a
     * discount somewhere downstream.
     */
    @Transactional(readOnly = true)
    public int availableToSell(Product catalogProduct) {
        return Math.max(0, catalogProduct.getQuantityOnHand() - committedQuantity(catalogProduct.getId()));
    }

    /**
     * Batch availability, for callers that project stock onto many products at once - the
     * public catalog grid, the {@code ?ids=} cart hydration, product detail and related
     * products all go through here.
     *
     * One query, always, however many ids are asked for. The per-product
     * {@link #availableToSell(Product)} stays for the checkout path, where there is
     * exactly one row and it is already locked.
     *
     * Ids that name no product are simply absent from the map rather than mapped to zero:
     * "no such product" and "sold out" are different answers, and a caller that conflates
     * them would advertise a deleted product as merely unavailable.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Integer> availableToSell(Collection<UUID> catalogProductIds) {
        if (catalogProductIds == null || catalogProductIds.isEmpty()) {
            // An empty IN list is a SQL syntax error, not an empty result.
            return Map.of();
        }
        return toIntMap(entityManager
                .createNativeQuery(AVAILABLE_TO_SELL_SQL)
                .setParameter("productIds", catalogProductIds)
                .getResultList());
    }

    /**
     * Batch committed quantities. Products with nothing committed are absent from the
     * map (the GROUP BY has no row for them), so callers should read a miss as zero.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Integer> committedQuantities(Collection<UUID> catalogProductIds) {
        if (catalogProductIds == null || catalogProductIds.isEmpty()) {
            return Map.of();
        }
        return toIntMap(entityManager
                .createNativeQuery(COMMITTED_QUANTITIES_SQL)
                .setParameter("productIds", catalogProductIds)
                .getResultList());
    }

    /** See {@link #FULLY_COMMITTED_SQL} - the exclusion set behind the storefront's in-stock filter. */
    @Transactional(readOnly = true)
    public Set<UUID> fullyCommittedProductIds(UUID sellerClientId) {
        return fullyCommittedProductIds(Set.of(sellerClientId));
    }

    /**
     * The same exclusion set across SEVERAL sellers at once - what the storefront
     * grid needs now that it spans every active seller rather than the platform
     * owner alone.
     *
     * One query for the whole page rather than one per seller: the grid is a
     * single query and its in-stock filter has to be too, or the count query
     * behind {@code totalElements} and the page contents would be assembled from
     * different snapshots.
     */
    @Transactional(readOnly = true)
    public Set<UUID> fullyCommittedProductIds(Collection<UUID> sellerClientIds) {
        if (sellerClientIds == null || sellerClientIds.isEmpty()) {
            // An empty IN list is a SQL syntax error, not an empty result - and with
            // no sellers there is nothing that could be sold out anyway.
            return Set.of();
        }
        @SuppressWarnings("unchecked")
        List<Object> ids = entityManager
                .createNativeQuery(FULLY_COMMITTED_SQL)
                .setParameter("sellerIds", sellerClientIds)
                .getResultList();
        return ids.stream().map(CatalogStockService::toUuid).collect(Collectors.toSet());
    }

    private static Map<UUID, Integer> toIntMap(List<?> rows) {
        Map<UUID, Integer> byProduct = new HashMap<>();
        for (Object row : rows) {
            Object[] columns = (Object[]) row;
            byProduct.put(toUuid(columns[0]), ((Number) columns[1]).intValue());
        }
        return byProduct;
    }

    /**
     * A native query returns whatever the JDBC driver chose for a uuid column, which is a
     * java.util.UUID on pgjdbc but a String on some pooling proxies. Normalising here
     * keeps that detail out of every caller's map lookups, where a mismatch would show up
     * as "every product is sold out" rather than as an error.
     */
    private static UUID toUuid(Object value) {
        return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
    }

    /**
     * The order-creation guard. Locks the catalog row for the rest of the caller's
     * transaction before recomputing availability, so two checkouts racing for the last
     * units serialise instead of both reading the same pre-sale number.
     *
     * The row is loaded with {@code EntityManager.find}, not a repository query: the
     * caller is the BUYER, so a JPQL load of the PLATFORM OWNER's product would be
     * filtered out from under it (see BuyerCatalogLookup). A by-id load is the one read
     * Hibernate does not apply {@code @Filter} to, and it takes the lock all the same.
     */
    @Transactional
    public void requireSellable(UUID catalogProductId, int quantity, String productName) {
        Product locked = entityManager.find(Product.class, catalogProductId, LockModeType.PESSIMISTIC_WRITE);
        if (locked == null) {
            throw new CheckoutNotAllowedException("\"" + productName + "\" is no longer sold.");
        }
        int available = availableToSell(locked);
        if (available < quantity) {
            throw new InsufficientCatalogStockException(productName, available);
        }
    }

    /**
     * The goods have left the building. Writes one OUT movement per line against the
     * SELLER's own product, priced at what it sold for, so their inventory, their
     * stock ledger and their analytics all reflect the sale.
     *
     * <p>The seller is read off the order, not assumed to be ProcurePal. Because an
     * order has exactly one seller (V11) and a multi-seller basket splits into one
     * order each (V12), every line here belongs to the same warehouse by
     * construction - which is what lets this run as one tenant-scoped block.
     *
     * <h2>Idempotency</h2>
     * Two layers. The caller reaches this only through
     * {@code OrderLifecycleService.transition}, which is gated by
     * {@code OrderStatus.canTransitionTo} - and OUT_FOR_DELIVERY is reachable only from
     * PROCESSING, so a second dispatch of the same order is rejected before any stock
     * moves. Under concurrency that check is made safe by the pessimistic lock the
     * fulfilment endpoint takes on the ORDER row, which serialises two simultaneous
     * dispatch requests so the loser sees OUT_FOR_DELIVERY and is refused. Each product
     * row is then locked in turn, so lines are never interleaved with another dispatch's
     * read-modify-write of quantity_on_hand.
     */
    @Transactional
    public void dispatch(Order order, UUID actingUserId) {
        // The SELLER's warehouse, not the platform owner's. Before vendors these
        // were always the same client and reading the platform owner here was
        // harmless shorthand; with third-party sellers it would deduct a vendor's
        // dispatch from ProcurePal's stock and leave the vendor's count untouched -
        // silently, because findByIdAndClientIdForUpdate would simply miss and the
        // loop below treats a miss as "hard-deleted, nothing to decrement".
        UUID sellerId = order.getSellerClientId();
        if (sellerId == null) {
            return;
        }
        tenantScopeExecutor.runAs(sellerId, () -> {
            for (OrderItem item : orderItemRepository.findAllByOrderIdOrderByCreatedAtAsc(order.getId())) {
                Product catalogProduct = productRepository
                        .findByIdAndClientIdForUpdate(item.getProductId(), sellerId)
                        .orElse(null);
                if (catalogProduct == null) {
                    // The catalog row was hard-deleted after the sale. Nothing to
                    // decrement; the order line's snapshots still describe what shipped.
                    log.warn(
                            "Order {} dispatched a line whose catalog product {} no longer exists",
                            order.getOrderNumber(),
                            item.getProductId());
                    continue;
                }
                // Clamped rather than allowed to throw: the goods are physically on the
                // van. Refusing to record a dispatch because the book count disagrees
                // would block a real delivery to fix a bookkeeping problem, and the
                // order-creation guard means this should be unreachable anyway.
                int quantity = Math.min(item.getQuantity(), catalogProduct.getQuantityOnHand());
                if (quantity < item.getQuantity()) {
                    log.warn(
                            "Order {} dispatched {} of {} units of {} - the seller's recorded stock was short",
                            order.getOrderNumber(),
                            quantity,
                            item.getQuantity(),
                            catalogProduct.getSku());
                }
                if (quantity <= 0) {
                    continue;
                }
                // Reused rather than reimplemented, exactly as the buyer-side receipt
                // does: StockManagementService owns the ledger row, the row lock and the
                // quantity_on_hand update.
                stockManagementService.stockOut(
                        catalogProduct.getId(),
                        new StockOutRequest(
                                quantity,
                                item.getUnitPrice(),
                                "Dispatched on marketplace order " + order.getOrderNumber()),
                        actingUserId);
            }
        });
    }
}
