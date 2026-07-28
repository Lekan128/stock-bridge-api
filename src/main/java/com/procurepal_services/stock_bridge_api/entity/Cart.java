package com.procurepal_services.stock_bridge_api.entity;

import com.procurepal_services.stock_bridge_api.tenant.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * One shared cart per COMPANY, not per user - enforced by the unique constraint
 * on client_id. This is B2B procurement: a storekeeper builds the requisition
 * over the week and an owner or procurement manager checks it out. A per-user
 * cart would make that ordinary workflow impossible.
 *
 * There is deliberately no @OneToMany to CartItem here. Cart is tenant-scoped
 * (so the Hibernate tenant filter applies to it) while CartItem is not, and its
 * lines point at the PLATFORM OWNER's products; keeping the collection out of
 * the entity forces every reader through CartItemRepository, where that
 * cross-tenant hop is explicit and documented.
 */
@Entity
@Table(name = "carts")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class Cart extends TenantAwareEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
