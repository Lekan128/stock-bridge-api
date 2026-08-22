package com.procurepal_services.stock_bridge_api.entity;

import com.procurepal_services.stock_bridge_api.tenant.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A marketplace purchase. The inherited client_id is the BUYER and
 * {@link #sellerClientId} is the SELLER.
 *
 * <h2>The seller used to be implicit, and no longer is</h2>
 * Before V11 the platform owner was the only seller there had ever been, so this
 * javadoc said the seller "is not stored". That is no longer true: any
 * {@link Client} with {@link ClientType#VENDOR}, as well as ProcurePal, can be on
 * the selling side of an order, and every order names which. Historical rows were
 * backfilled with the platform owner's id, which is what they always meant.
 *
 * <h2>One order per seller</h2>
 * A cart may hold products from several sellers; at checkout it SPLITS into one
 * Order per seller. An order is a fulfilment contract with one counterparty - one
 * dispatcher, one stock deduction, one status timeline, one payout - and an order
 * spanning two sellers would have to be half-dispatched, with every
 * {@link OrderStatus} transition qualified by seller.
 *
 * <p>That is also why {@link OrderItem} carries no seller: every line of an order
 * shares this one by construction, so a per-line copy could only ever drift from
 * it, and a line whose seller disagrees with its order's has no defined meaning.
 * See the orders section of V11__vendors.sql.
 *
 * <p>The split is implemented, not merely intended: {@code OrderService.place}
 * groups the priced cart by seller and writes one of these per group, tied
 * together by {@link #checkoutGroupId}. Read that field's javadoc before touching
 * anything that assumes one checkout equals one order - notably payment, which is
 * where the assumption was load-bearing.
 *
 * <h2>Tenant scoping, and the one place it has to be lifted</h2>
 * Order extends TenantAwareEntity so a buyer can only ever see their own orders,
 * twice over (Hibernate filter + explicit client_id predicates). But a SELLER's
 * fulfilment queue legitimately needs to read EVERY buyer's orders, and under the
 * seller's own tenant filter that returns nothing. That is not a reason to drop
 * tenant scoping here - it is the documented exception, and it goes through a
 * guarded escape hatch that asserts the caller's standing before lifting the
 * filter: {@code VendorGuard.readOwnSales(...)} for any seller (vendors and
 * ProcurePal alike, which is what the fulfilment queue uses), and
 * {@code PlatformOwnerGuard.readAcrossTenants(...)} for the operator-only surfaces
 * that genuinely read across tenants, such as the category in-use count. Never
 * disable the filter by hand.
 *
 * <p>Analytics is not on that list any more and never really needed to be: both
 * the marketplace and vendor analytics modules use native SQL, which the Hibernate
 * filter does not touch at all, so neither takes a hatch. Since M6 both are also
 * pinned to one {@code seller_client_id} - ProcurePal's own analytics report
 * ProcurePal's own sales, not the marketplace's. Cross-seller totals live on the
 * super admin surface, where there is no tenant filter to lift in the first place
 * because a super admin principal never sets one.
 *
 * <p>Both hatches lift layer 1 and put NOTHING in its place. Every query inside
 * one must add its own predicate - {@code seller_client_id} for a seller's own
 * sales. A read inside {@code readOwnSales} without it would hand one vendor
 * every other vendor's orders, which is the worst failure this feature can
 * produce and would be entirely silent.
 *
 * <h2>Two status axes, not one</h2>
 * {@code status} is fulfilment and {@code paymentStatus} is money. See
 * {@link OrderStatus} for the transition rules (which live there, not in a
 * service) and the orders table comment in V6__marketplace.sql for why they are
 * not a single column.
 *
 * <h2>Snapshots</h2>
 * The delivery* fields are copied at checkout, not read through
 * deliveryAddressId. An order is a contract: editing or deleting an address must
 * never rewrite the shipping details of an order that already went out. Same
 * reasoning applies to the per-line snapshots on {@link OrderItem}.
 *
 * placedBy / branchId / deliveryAddressId / sellerClientId are raw UUIDs rather
 * than associations because ProcurePal reads these rows cross-tenant; see
 * CartItem for the full explanation of why a mapped association to a
 * tenant-scoped entity is a trap in that direction.
 */
@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class Order extends TenantAwareEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Human-readable and quotable, e.g. 'PP-2026-000123'. Unique across the platform. */
    @Column(name = "order_number", nullable = false, updatable = false, length = 30)
    private String orderNumber;

    /**
     * Who sold this: a vendor's client id, or the platform owner's. NOT NULL in
     * the schema - every order has exactly one seller, so no query needs a NULL
     * branch and nothing has to fall back to "presumably ProcurePal".
     *
     * <p>A vendor's order queue is "orders where sellerClientId is me". That is a
     * cross-tenant read - orders are tenant-scoped to the BUYER - so it must go
     * through the guarded escape hatch, exactly as ProcurePal's fulfilment queue
     * does. Never disable the tenant filter by hand to serve it.
     */
    @Column(name = "seller_client_id", nullable = false)
    private UUID sellerClientId;

    /**
     * Which checkout produced this order. Every order that came out of one press
     * of "place order" carries the same value; a single-seller basket produces a
     * group of one.
     *
     * <p>It exists because the split makes "one purchase" and "one order" stop
     * being the same thing. Three things need the distinction back: the buyer's
     * confirmation screen ("your basket became 3 orders"), their order history
     * (which would otherwise read as three unrelated purchases sharing a
     * timestamp), and - the load-bearing one - PAYMENT. A buyer pays once, so one
     * Monnify transaction has to settle all N orders atomically. The payments row
     * anchors on one order of the group and settlement fans out across this
     * column; see {@code PaymentApplicationService}.
     *
     * <p>Deliberately not a foreign key to a checkout_groups table: a group has no
     * attribute that is not already identical on every member (same buyer, same
     * address, same instant, same payment method, by construction), so a parent
     * row would only be a second place for those facts to live. See
     * V12__order_splitting.sql.
     */
    @Column(name = "checkout_group_id", nullable = false, updatable = false)
    private UUID checkoutGroupId;

    @Column(name = "placed_by")
    private UUID placedBy;

    @Column(name = "branch_id")
    private UUID branchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 30)
    private PaymentStatus paymentStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 30)
    private PaymentMethod paymentMethod;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "delivery_fee", nullable = false, precision = 14, scale = 2)
    private BigDecimal deliveryFee;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal total;

    /** Kept only so "reuse this address" and analytics can point at the original row. */
    @Column(name = "delivery_address_id")
    private UUID deliveryAddressId;

    @Column(name = "delivery_label", length = 100)
    private String deliveryLabel;

    @Column(name = "delivery_contact_name")
    private String deliveryContactName;

    @Column(name = "delivery_contact_phone", length = 50)
    private String deliveryContactPhone;

    @Column(name = "delivery_address_line1")
    private String deliveryAddressLine1;

    @Column(name = "delivery_address_line2")
    private String deliveryAddressLine2;

    @Column(name = "delivery_city", length = 100)
    private String deliveryCity;

    @Column(name = "delivery_state", length = 100)
    private String deliveryState;

    @Column(name = "delivery_landmark")
    private String deliveryLandmark;

    @Column(name = "delivery_notes", length = 500)
    private String deliveryNotes;

    @Column(name = "customer_note", length = 1000)
    private String customerNote;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Column(name = "placed_at")
    private OffsetDateTime placedAt;

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    @Column(name = "dispatched_at")
    private OffsetDateTime dispatchedAt;

    @Column(name = "delivered_at")
    private OffsetDateTime deliveredAt;

    @Column(name = "received_at")
    private OffsetDateTime receivedAt;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
