package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.OrderItem;
import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.PaymentStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Plain JpaRepository: order_items has no client_id (a line belongs to its order,
 * which already records the buyer), so nothing here is tenant-filtered. That is
 * what lets ProcurePal read order lines without lifting any filter - and it means
 * the caller must have already authorised access to the ORDER before calling
 * these. Resolve the order through OrderRepository first.
 */
public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {

    List<OrderItem> findAllByOrderIdOrderByCreatedAtAsc(UUID orderId);

    /**
     * Receiving a delivery accepts a list of (orderItemId, quantity) pairs from the
     * client, so every line must be re-checked against the order it is claimed to
     * belong to before its quantity is trusted.
     */
    Optional<OrderItem> findByIdAndOrderId(UUID id, UUID orderId);

    long countByOrderId(UUID orderId);

    /**
     * Whether any line still points at this buyer product - the safety check before deleting an
     * auto-created row a receipt just relinked away from (IncomingStockService.receive's
     * orphanedDuplicate cleanup). Deleting a row another line still references would leave that
     * line's buyerProductId dangling.
     */
    boolean existsByBuyerProductId(UUID buyerProductId);

    /** Reorder / "you bought this before": every line ever bought for one catalog product. */
    List<OrderItem> findAllByProductId(UUID productId);

    /**
     * Every line this buyer ever bought from this seller for a given set of their
     * OWN inventory rows, newest purchase first. Reading the first hit per
     * buyerProductId gives "what did we last pay this supplier for this item" -
     * VENDOR_RESEARCH.md Section B's highest-value buyer field, and the thing that
     * makes a vendor record more than a contacts app.
     *
     * <h2>Why the buyer's client_id is in the predicate</h2>
     * order_items has no client_id of its own and is therefore not tenant-filtered,
     * so the ONLY thing scoping this query is {@code oi.order.clientId}. That is
     * not belt-and-braces here the way it is elsewhere - it is the entire isolation
     * story for this read, and removing it would return every company's purchase
     * prices. The join is written explicitly rather than left implicit for exactly
     * that reason: the predicate should be impossible to miss.
     *
     * <h2>Why it is scoped by seller as well as by product</h2>
     * The same buyer product can be bought from several suppliers. Filtering on
     * buyerProductId alone would answer "what did we last pay for this", which is a
     * different question and would attribute one supplier's price to another.
     *
     * <p>Cancelled orders are excluded: a cancelled order is a price nobody paid.
     * Orders that never reached PLACED are excluded for the same reason.
     */
    @Query("SELECT oi FROM OrderItem oi JOIN oi.order o "
            + "WHERE o.clientId = :buyerClientId AND o.sellerClientId = :sellerClientId "
            + "AND o.placedAt IS NOT NULL AND o.status <> :excludedStatus "
            + "AND oi.buyerProductId IN :buyerProductIds "
            + "ORDER BY o.placedAt DESC, oi.createdAt DESC")
    List<OrderItem> findPurchaseLinesForBuyerProducts(
            @Param("buyerClientId") UUID buyerClientId,
            @Param("sellerClientId") UUID sellerClientId,
            @Param("buyerProductIds") Collection<UUID> buyerProductIds,
            @Param("excludedStatus") OrderStatus excludedStatus);

    /**
     * The lines of several orders at once, so the purchase-history page issues one
     * query for its whole page instead of one per order.
     */
    List<OrderItem> findAllByOrderIdInOrderByCreatedAtAsc(Collection<UUID> orderIds);

    /**
     * The escrow "pending" figure, line by line: goods a vendor has sold and been
     * paid for by the buyer, which have NOT yet been confirmed delivered and
     * therefore have no ledger entry at all.
     *
     * <h2>Why this is not in the ledger</h2>
     * Because nothing has happened to the vendor's money yet. The platform is
     * holding the BUYER's money and the vendor has not earned it; posting an entry
     * would assert an obligation the stakeholder explicitly refused to create
     * before delivery. So "pending escrow" is derived from orders, and this is
     * the derivation. See V14 for the three escrow states.
     *
     * <h2>The predicates</h2>
     * PAID only - a PENDING_PAYMENT order is a basket nobody paid for, and
     * pay-on-delivery is refused outright for baskets containing vendor goods
     * ({@code CheckoutService.payOnDeliveryReasons}), which is precisely what makes
     * this escrow real rather than notional: every vendor order in scope is
     * prepaid, so the platform genuinely holds the money.
     *
     * <p>CANCELLED excluded: a cancelled order will be refunded, not delivered.
     *
     * <p>{@code NOT EXISTS} a SALE_PROCEEDS entry: once a line has accrued it is in
     * the ledger and counted there, and counting it in both places would show a
     * vendor the same money twice.
     *
     * <p>Returns a PROJECTION rather than a SUM because the commission on the lines
     * has to be computed by {@code VendorCommission}, which is the single place the
     * rounding rule lives. Summing in SQL would put a second, subtly different
     * implementation of that rule in the database.
     *
     * <p>A projection rather than the {@code OrderItem} entities, too: reading
     * {@code item.getOrder()} back would resolve a lazy proxy to a tenant-scoped
     * row belonging to the BUYER, under whatever filter the caller happens to be
     * running with. The columns are what the caller needs, so nothing has to be
     * navigated. Each row is {@code [orderId, lineTotal, commissionRate]}.
     *
     * <p>Callers must still run this inside {@code VendorGuard.readOwnSales(...)}:
     * the join to {@code orders} is a join to a filtered entity, and under the
     * seller's own tenant filter it would match nothing at all.
     */
    @Query("SELECT o.id, oi.lineTotal, oi.commissionRate FROM OrderItem oi JOIN oi.order o "
            + "WHERE o.sellerClientId = :sellerClientId "
            + "AND o.status <> :cancelledStatus "
            + "AND o.paymentStatus = :paidStatus "
            + "AND NOT EXISTS (SELECT 1 FROM VendorLedgerEntry e "
            + "                WHERE e.orderItemId = oi.id "
            + "                AND e.entryType = com.procurepal_services.stock_bridge_api.entity"
            + ".VendorLedgerEntryType.SALE_PROCEEDS) "
            + "ORDER BY o.placedAt ASC, oi.createdAt ASC")
    List<Object[]> findLinesAwaitingAccrual(
            @Param("sellerClientId") UUID sellerClientId,
            @Param("cancelledStatus") OrderStatus cancelledStatus,
            @Param("paidStatus") PaymentStatus paidStatus);

    /**
     * The order context a statement line needs: the quotable order number and the
     * product name as it was snapshotted at sale time.
     *
     * <h2>Why a projection and why by id</h2>
     * The ids come from the caller's OWN ledger rows, so this cannot be used to
     * reach another seller's lines by passing arbitrary ids - there is no way to
     * obtain an id that is not already yours. It is still a projection rather than
     * entities, for the same reason {@link #findLinesAwaitingAccrual} is: the join
     * crosses into {@code orders}, which belongs to the BUYER, and reading an
     * entity back would resolve a lazy proxy under whatever tenant filter the
     * caller happens to be running with.
     *
     * <p>Must be called inside {@code VendorGuard.readOwnSales(...)} - the join to
     * the tenant-filtered {@code orders} matches nothing under a seller's own
     * filter. Each row is {@code [orderItemId, orderNumber, productName, quantity]}.
     */
    @Query("SELECT oi.id, o.orderNumber, oi.productName, oi.quantity FROM OrderItem oi JOIN oi.order o "
            + "WHERE oi.id IN :orderItemIds")
    List<Object[]> findStatementContext(@Param("orderItemIds") Collection<UUID> orderItemIds);
}
