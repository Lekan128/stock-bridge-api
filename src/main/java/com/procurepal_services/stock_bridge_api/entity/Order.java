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
 * A marketplace purchase. The inherited client_id is the BUYER - the platform
 * owner is always the seller, so it is not stored.
 *
 * <h2>Tenant scoping, and the one place it has to be lifted</h2>
 * Order extends TenantAwareEntity so a buyer can only ever see their own orders,
 * twice over (Hibernate filter + explicit client_id predicates). But ProcurePal's
 * fulfilment queue legitimately needs to read EVERY buyer's orders, and under
 * ProcurePal's own tenant filter that returns nothing. That is not a reason to
 * drop tenant scoping here - it is the single documented exception, and it goes
 * through {@code PlatformOwnerGuard.readAcrossTenants(...)}, which asserts the
 * caller really is the platform owner before it lifts the filter. Never disable
 * the filter by hand.
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
 * placedBy / branchId / deliveryAddressId are raw UUIDs rather than associations
 * because ProcurePal reads these rows cross-tenant; see CartItem for the full
 * explanation of why a mapped association to a tenant-scoped entity is a trap in
 * that direction.
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
