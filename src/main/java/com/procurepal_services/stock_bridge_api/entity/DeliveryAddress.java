package com.procurepal_services.stock_bridge_api.entity;

import com.procurepal_services.stock_bridge_api.tenant.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A physical location this company keeps in its address book - either somewhere
 * it wants goods delivered TO, or, for a seller, somewhere its goods are
 * COLLECTED FROM. Tenant-scoped, and {@link AddressPurpose} says which.
 *
 * The class name predates the second meaning and is kept because the table,
 * {@code orders.delivery_address_id} and every existing caller are named for it;
 * see {@link AddressPurpose} for why the two kinds share one table and what that
 * obliges every query here to carry.
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

    /**
     * Delivery address or pickup point. Defaults to {@link AddressPurpose#DELIVERY}
     * so a caller that predates the column - or a future one that forgets it -
     * creates a buyer address, which is the harmless answer on a pickup screen and
     * the correct one at checkout. Matches the column default V13 backfilled every
     * existing row with.
     */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "address_purpose", nullable = false, length = 20)
    private AddressPurpose purpose = AddressPurpose.DELIVERY;

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
