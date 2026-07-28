package com.procurepal_services.stock_bridge_api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
 * A tenant. slug is the human-readable identifier a tenant's users log in
 * with, distinct from the surrogate id used for foreign keys.
 */
@Entity
@Table(name = "clients")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Client {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(name = "admin_contact_email", nullable = false)
    private String adminContactEmail;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(length = 50)
    private String phone;

    /**
     * ProcurePal itself. At most one client can have this set - enforced by a
     * partial unique index, not just by service code, because two platform owners
     * would silently split the public catalog in two.
     *
     * Deliberately a flag rather than a separate entity: the platform owner does
     * everything an ordinary tenant does (its own inventory, users, analytics) and
     * a distinct entity would mean duplicating every tenant-scoped relationship.
     *
     * Read it through PlatformOwnerGuard rather than inline, so the 403 behaviour
     * for marketplace-admin surfaces is defined in exactly one place.
     */
    @Column(name = "is_platform_owner", nullable = false)
    private boolean platformOwner;

    /**
     * Whether this company may choose pay-on-delivery at checkout. Lives on the
     * client because it is a commercial relationship decision ProcurePal ops makes
     * about a customer, not a per-order choice. PREPAID by default: a brand-new
     * signup has no trading history, so it pays before we ship.
     */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_terms", nullable = false, length = 30)
    private PaymentTerms paymentTerms = PaymentTerms.PREPAID;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * Convenience for checkout: this is only one of three gates on pay-on-delivery
     * (see MarketplaceSettings for the other two), so a caller must still check
     * those - it exists so nobody has to remember the enum constant's name.
     */
    public boolean isPayOnDeliveryAllowed() {
        return paymentTerms != null && paymentTerms.allowsPayOnDelivery();
    }
}
