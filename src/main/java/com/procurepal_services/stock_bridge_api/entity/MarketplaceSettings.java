package com.procurepal_services.stock_bridge_api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * ProcurePal's commercial rules, as data rather than configuration. These are
 * decisions ops makes and changes (a delivery fee, a free-delivery threshold, a
 * pay-on-delivery cap), not deployment concerns, so they belong in a table an
 * admin screen can edit - not in environment variables that need a redeploy.
 *
 * Exactly one row exists, which the DB enforces via the `singleton` column's
 * unique index plus a CHECK that it is always TRUE. Not tenant-scoped: there is
 * one marketplace.
 */
@Entity
@Table(name = "marketplace_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketplaceSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Always true; it exists only to carry the unique index that makes this a single-row table. */
    @Column(nullable = false)
    private boolean singleton;

    @Column(name = "delivery_fee", nullable = false, precision = 14, scale = 2)
    private BigDecimal deliveryFee;

    /** Order subtotal at or above which delivery is free. */
    @Column(name = "free_delivery_threshold", nullable = false, precision = 14, scale = 2)
    private BigDecimal freeDeliveryThreshold;

    @Column(name = "minimum_order_value", nullable = false, precision = 14, scale = 2)
    private BigDecimal minimumOrderValue;

    @Column(name = "pay_on_delivery_enabled", nullable = false)
    private boolean payOnDeliveryEnabled;

    /** A cap on trust: fine for a restaurant restock, not for a freezer order. */
    @Column(name = "pay_on_delivery_max_order_value", nullable = false, precision = 14, scale = 2)
    private BigDecimal payOnDeliveryMaxOrderValue;

    @Column(name = "support_phone", length = 50)
    private String supportPhone;

    @Column(name = "support_email")
    private String supportEmail;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
