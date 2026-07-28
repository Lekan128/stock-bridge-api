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
 * A company location. Tenant-scoped, so it inherits client_id from
 * TenantAwareEntity.
 *
 * Every client has exactly one 'Head Office' branch flagged default - created by
 * ClientSignupService for new signups and backfilled by V6__marketplace.sql for
 * clients that predate it. Stock is NOT scoped per branch in this pass
 * (products.quantity_on_hand stays client-wide); see the branches table comment
 * in the migration for what per-branch stock would actually require.
 *
 * The boolean is named defaultBranch rather than isDefault because `default` is
 * a Java keyword, and a property literally called "default" makes both Lombok's
 * accessor naming and Spring Data's derived-query parsing ambiguous.
 */
@Entity
@Table(name = "branches")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class Branch extends TenantAwareEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "is_default", nullable = false)
    private boolean defaultBranch;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
