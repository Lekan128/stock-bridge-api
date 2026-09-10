package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.ClientType;
import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.PaymentStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Order is tenant-scoped, so everything here is safe for a buyer by default:
 * findByIdForCurrentTenant / the client_id-carrying finders below hold even with
 * the Hibernate filter off, which is exactly what §6 of the marketplace contract
 * requires ("assert via client_id in the repository query, not just the filter").
 *
 * The platform owner's fulfilment queue is the one caller that needs to see other
 * tenants' rows. It must wrap its query in
 * {@code PlatformOwnerGuard.readAcrossTenants(...)} - the JpaSpecificationExecutor
 * methods and the findAllByStatus* finders here return nothing under ProcurePal's
 * own tenant filter otherwise. That guard asserts platform ownership before it
 * lifts the filter, so cross-tenant reads stay auditable in one place.
 */
public interface OrderRepository extends TenantScopedRepository<Order, UUID>, JpaSpecificationExecutor<Order> {

    Optional<Order> findByOrderNumber(String orderNumber);

    boolean existsByOrderNumber(String orderNumber);

    /**
     * Feeds order-number allocation (e.g. count of 'PP-2026-' rows + 1). A count is
     * good enough because uq_orders_order_number is the real guard: a concurrent
     * allocation collides on insert and the caller retries, rather than two orders
     * quietly sharing a number.
     */
    long countByOrderNumberStartingWith(String prefix);

    Page<Order> findAllByClientIdOrderByCreatedAtDesc(UUID clientId, Pageable pageable);

    Page<Order> findAllByClientIdAndStatusOrderByCreatedAtDesc(
            UUID clientId, OrderStatus status, Pageable pageable);

    List<Order> findAllByClientIdAndStatusInOrderByCreatedAtDesc(UUID clientId, List<OrderStatus> statuses);

    long countByClientId(UUID clientId);

    long countByClientIdAndStatus(UUID clientId, OrderStatus status);

    /** Platform-owner fulfilment queue. Call inside readAcrossTenants(...). */
    Page<Order> findAllByStatusOrderByCreatedAtDesc(OrderStatus status, Pageable pageable);

    /**
     * The abandoned-checkout sweep: orders left at PENDING_PAYMENT past their
     * grace period get cancelled so they stop cluttering the buyer's list and
     * ProcurePal's queue.
     */
    List<Order> findAllByStatusAndCreatedAtBefore(OrderStatus status, OffsetDateTime createdBefore);

    /**
     * Lifetime spend for one customer, for the marketplace customers view. JPQL
     * (rather than a derived query) because the SUM has to COALESCE - a customer
     * with no paid orders must read as 0, not null.
     */
    @Query("SELECT COALESCE(SUM(o.total), 0) FROM Order o "
            + "WHERE o.clientId = :clientId AND o.paymentStatus = :paymentStatus")
    BigDecimal sumTotalByClientIdAndPaymentStatus(
            @Param("clientId") UUID clientId, @Param("paymentStatus") PaymentStatus paymentStatus);

    // ------------------------------------------------------------------------
    // The SELLING side (V11).
    //
    // orders.client_id is the buyer, so every finder below reads OTHER tenants'
    // rows and returns nothing under the seller's own tenant filter. They must be
    // called inside PlatformOwnerGuard.readAcrossTenants(...) or
    // VendorGuard.readOwnSales(...) - the guarded escape hatches - never after
    // disabling the filter by hand.
    //
    // The seller_client_id predicate is what scopes the result, and it is the
    // whole isolation story on this side: it is the seller-facing mirror of the
    // explicit client_id predicates everywhere else in this interface. A vendor
    // querying without it would see every seller's orders.
    // ------------------------------------------------------------------------

    /** A seller's order queue: their orders, newest first. */
    Page<Order> findAllBySellerClientIdOrderByCreatedAtDesc(UUID sellerClientId, Pageable pageable);

    Page<Order> findAllBySellerClientIdAndStatusOrderByCreatedAtDesc(
            UUID sellerClientId, OrderStatus status, Pageable pageable);

    /**
     * One order, scoped to the seller it belongs to. The seller-side equivalent of
     * findByIdAndClientId: a vendor asking for an order id that is not theirs must
     * get nothing back rather than somebody else's customer and delivery address.
     */
    Optional<Order> findByIdAndSellerClientId(UUID id, UUID sellerClientId);

    long countBySellerClientId(UUID sellerClientId);

    long countBySellerClientIdAndStatus(UUID sellerClientId, OrderStatus status);

    /** A seller's lifetime paid revenue, for their own sales analytics. COALESCEd for the same reason as above. */
    @Query("SELECT COALESCE(SUM(o.total), 0) FROM Order o "
            + "WHERE o.sellerClientId = :sellerClientId AND o.paymentStatus = :paymentStatus")
    BigDecimal sumTotalBySellerClientIdAndPaymentStatus(
            @Param("sellerClientId") UUID sellerClientId, @Param("paymentStatus") PaymentStatus paymentStatus);

    /**
     * Does this client have any order against them as seller? Read before a
     * destructive operator action - orders.seller_client_id is ON DELETE RESTRICT,
     * so the answer decides whether a vendor account can be removed at all, and a
     * clear "they have sold things" beats a raw foreign-key violation.
     */
    boolean existsBySellerClientId(UUID sellerClientId);

    // ------------------------------------------------------------------------
    // Checkout groups (V12) - the orders one basket produced.
    //
    // Both finders below cross tenants in the same direction the seller finders
    // do, and for a sharper reason: the hottest caller is the Monnify webhook,
    // which arrives with NO authenticated principal and therefore no tenant
    // filter at all. The checkout_group_id predicate is what scopes them, and it
    // is sufficient - a group id is a random UUID minted server-side at checkout
    // and never exposed to a caller who did not place the basket, so it cannot be
    // guessed or enumerated. Buyer-facing callers must STILL apply their own
    // client_id check on top; see OrderService.
    // ------------------------------------------------------------------------

    /**
     * Every order that came out of one checkout, in the order they were written -
     * which is seller-name order, so a buyer's confirmation screen and their
     * emailed receipt list the same sub-orders the same way round on every render.
     */
    List<Order> findAllByCheckoutGroupIdOrderByOrderNumberAsc(UUID checkoutGroupId);

    /**
     * The buyer-scoped form. Used by the confirmation screen, where the group id
     * arrives from a URL: the client_id predicate is what stops a buyer pasting
     * somebody else's group id and reading their delivery address.
     */
    List<Order> findAllByCheckoutGroupIdAndClientIdOrderByOrderNumberAsc(UUID checkoutGroupId, UUID clientId);

    // ------------------------------------------------------------------------
    // The buyer-side vendor directory: purchase history and spend (M5).
    //
    // These are ORDINARY IN-TENANT READS and must stay that way. orders.client_id
    // is the BUYER, and the buyer is exactly who is asking - "what have I bought
    // from this supplier" is a question about my own orders, narrowed by who sold
    // them. Nothing here needs readAcrossTenants; if a finder below ever seems to,
    // the predicate has been written backwards (seller asking about buyers rather
    // than buyer asking about sellers) and lifting the filter would answer the
    // wrong question with somebody else's data.
    //
    // WHAT COUNTS AS A PURCHASE, and why placedAt is the predicate:
    // an order that reached PLACED. Before that a Monnify order is a hopeful
    // basket that may never be paid for, and counting it would tell a buyer they
    // had spent money they have not. It is also the exact moment the VERIFIED
    // directory row itself is created, so a vendor and its history come into
    // existence together. Cancellation is handled per-caller: cancelled orders
    // stay in the history LIST (badged) and are excluded from the money.
    // ------------------------------------------------------------------------

    /**
     * The purchase-history screen: this buyer's orders from one seller, newest
     * first, cancelled ones included - see VendorPurchaseResponse for why.
     */
    Page<Order> findAllByClientIdAndSellerClientIdAndPlacedAtIsNotNullOrderByPlacedAtDesc(
            UUID clientId, UUID sellerClientId, Pageable pageable);

    /** Order count for the vendor's spend summary. Excludes the cancelled ones. */
    long countByClientIdAndSellerClientIdAndPlacedAtIsNotNullAndStatusNot(
            UUID clientId, UUID sellerClientId, OrderStatus excludedStatus);

    /**
     * Lifetime spend with one supplier. JPQL rather than a derived query for the
     * same reason sumTotalByClientIdAndPaymentStatus above is: the SUM has to
     * COALESCE, or a supplier whose only order was cancelled reads as null instead
     * of as zero, and null formats as an empty box where a real 0 belongs.
     */
    @Query("SELECT COALESCE(SUM(o.total), 0) FROM Order o "
            + "WHERE o.clientId = :clientId AND o.sellerClientId = :sellerClientId "
            + "AND o.placedAt IS NOT NULL AND o.status <> :excludedStatus")
    BigDecimal sumTotalByClientIdAndSellerClientId(
            @Param("clientId") UUID clientId,
            @Param("sellerClientId") UUID sellerClientId,
            @Param("excludedStatus") OrderStatus excludedStatus);

    /**
     * When this company last bought from this supplier. Null - correctly - when
     * they never have; MAX over an empty set is null rather than an error, which is
     * the answer the screen wants.
     */
    @Query("SELECT MAX(o.placedAt) FROM Order o "
            + "WHERE o.clientId = :clientId AND o.sellerClientId = :sellerClientId "
            + "AND o.placedAt IS NOT NULL AND o.status <> :excludedStatus")
    OffsetDateTime findLastPurchasedAt(
            @Param("clientId") UUID clientId,
            @Param("sellerClientId") UUID sellerClientId,
            @Param("excludedStatus") OrderStatus excludedStatus);

    // ------------------------------------------------------------------------
    // Settlement (M7). See com.procurepal_services.stock_bridge_api.settlement.
    // ------------------------------------------------------------------------

    /**
     * The escrow release sweep's candidate list: vendor orders that ProcurePal
     * marked DELIVERED some time ago, whose buyer never confirmed receipt, and
     * which have not accrued yet.
     *
     * <h2>Why this query lives here and not in the settlement package</h2>
     * Its root is {@code Order} and its expensive predicates are order predicates.
     * The {@code NOT EXISTS} over the ledger is what keeps the sweep from
     * re-considering the same orders every five minutes forever - it is a filter,
     * not the query's subject.
     *
     * <h2>Tenant scoping</h2>
     * There is none, deliberately, and this is the one finder here that says so out
     * loud. It runs on a scheduler thread where TenantContext is empty and the
     * Hibernate filter was never enabled - exactly the situation
     * {@code PaymentReconciliationService}'s sweeps run in, and correct for a
     * platform-wide job. The seller predicate is on {@code clientType}, not on a
     * caller: this deliberately walks every vendor.
     *
     * <p>Only {@code ClientType.VENDOR} sellers. ProcurePal's own sales never
     * accrue - the platform would be posting a debt to itself - so including them
     * would make the sweep do work whose only possible outcome is an entry nobody
     * should ever have written.
     */
    @Query("SELECT o FROM Order o "
            + "WHERE o.status = :deliveredStatus "
            + "AND o.deliveredAt IS NOT NULL AND o.deliveredAt < :deliveredBefore "
            + "AND EXISTS (SELECT 1 FROM Client c WHERE c.id = o.sellerClientId AND c.clientType = :vendorType) "
            + "AND NOT EXISTS (SELECT 1 FROM VendorLedgerEntry e WHERE e.orderId = o.id) "
            + "ORDER BY o.deliveredAt ASC")
    List<Order> findVendorOrdersAwaitingEscrowRelease(
            @Param("deliveredStatus") OrderStatus deliveredStatus,
            @Param("deliveredBefore") OffsetDateTime deliveredBefore,
            @Param("vendorType") ClientType vendorType,
            Pageable pageable);
}
