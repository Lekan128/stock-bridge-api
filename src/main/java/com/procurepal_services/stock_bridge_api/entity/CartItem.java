package com.procurepal_services.stock_bridge_api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
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
 * A line in a company's shared cart. Explicitly NOT a TenantAwareEntity, and it
 * has no client_id column: productId points at the PLATFORM OWNER's product
 * while the reader is the buyer, so a client_id filter here would be wrong in
 * one direction or the other. A cart line is scoped through its cart, which IS
 * tenant-scoped - always resolve the cart first, then its items.
 *
 * <h2>Why productId is a raw UUID and not a @ManyToOne Product</h2>
 * Product extends TenantAwareEntity, so the Hibernate tenant filter is active on
 * it for the whole request. Under the buyer's filter, any query that JOINs to
 * the platform owner's product row matches nothing, so a mapped association
 * would work through lazy by-id loads (which bypass filters) and silently return
 * empty for join fetches. Rather than leave that trap for callers, the pointer is
 * a plain id: load catalog products deliberately, with the platform owner's
 * client_id, via ProductRepository's marketplace finders.
 *
 * addedBy is a same-tenant user, so it is safe as a mapped association, and it is
 * what keeps a shared cart accountable ("Chidi added 4 bags").
 */
@Entity
@Table(name = "cart_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CartItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id", nullable = false, updatable = false)
    private Cart cart;

    /** The platform owner's catalog product. See the class doc for why this is not an association. */
    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(nullable = false)
    private int quantity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "added_by")
    private User addedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
