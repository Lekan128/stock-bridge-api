package com.procurepal_services.stock_bridge_api.entity;

import com.procurepal_services.stock_bridge_api.tenant.TenantAwareEntity;
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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Where a company wants goods delivered. Tenant-scoped.
 *
 * Deliberately separate from {@link Branch}: a single-branch company still ships
 * to a main kitchen and a warehouse, and forcing them to invent branches for
 * that would pollute a structural concept with a logistics one. The optional
 * branch association is same-tenant by construction, so it is safe to navigate.
 *
 * Deactivated (soft-deleted) addresses are kept forever because orders reference
 * them, and are excluded from the "one default per client" rule - otherwise
 * removing the default would block promoting a replacement.
 *
 * Nigeria only: state is one of the 36 states + FCT and there is no country
 * column. See the migration for why that is a deliberate choice, not an omission.
 */
@Entity
@Table(name = "delivery_addresses")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class DeliveryAddress extends TenantAwareEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    @Column(nullable = false, length = 100)
    private String label;

    @Column(name = "contact_name", nullable = false)
    private String contactName;

    @Column(name = "contact_phone", nullable = false, length = 50)
    private String contactPhone;

    @Column(name = "address_line1", nullable = false)
    private String addressLine1;

    @Column(name = "address_line2")
    private String addressLine2;

    @Column(nullable = false, length = 100)
    private String city;

    @Column(nullable = false, length = 100)
    private String state;

    @Column
    private String landmark;

    @Column(name = "delivery_notes", length = 500)
    private String deliveryNotes;

    /** Named defaultAddress, not isDefault - see Branch for why. */
    @Column(name = "is_default", nullable = false)
    private boolean defaultAddress;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
