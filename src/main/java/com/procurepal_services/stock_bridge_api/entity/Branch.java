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
 * Every client has exactly one branch flagged default - its main branch, 'Head Office'
 * until renamed - created by ClientSignupService for new signups and backfilled by
 * V6__marketplace.sql for clients that predate it. Since V51 stock is counted per
 * branch underneath (branch_stock, stock_movements.branch_id); see BranchStockService
 * for how a one-branch company's figures still read exactly as before, and BranchService
 * for adding the second.
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

    /** The branch's own number, for the waybill and the people sending stock there (V52). Optional. */
    @Column(length = 50)
    private String phone;

    /**
     * The short code on its waybills - "BOL" in WB-BOL-0042 (V53). Given by the database when the branch
     * is created (V57's trigger), from its name, unique in the company; never written from here, so a
     * branch saved before it was read back can't overwrite it with null.
     */
    @Column(length = 8, insertable = false, updatable = false)
    private String code;

    /** When it was closed (V52); null while open. Closing keeps every row that names the branch. */
    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
