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
 * A business asking to sell on the marketplace, and the super admin's decision
 * about it.
 *
 * <h2>Deliberately NOT tenant-scoped</h2>
 * An applicant is by definition not a tenant yet: there is no {@code clients} row
 * to scope them to. The form is submitted from a public page with no
 * {@code TenantContext} at all, where {@link com.procurepal_services.stock_bridge_api.tenant.TenantAwareEntity}'s
 * {@code @PrePersist} would refuse to persist rather than guess - and it is read
 * by super admins, who are not tenant principals either. Same reasoning V6 gives
 * for payments and payment webhook events, and V10 for email suppressions:
 * whether this row exists is a fact about the platform, not about a tenant.
 *
 * <h2>Why email is required here but not on the vendor's Client</h2>
 * An applicant reached us through a form, and the address they left is the only
 * way to reply to them - it is their identifier until they have an account. A
 * super admin adding a vendor directly may have met them in person and have no
 * email at all, which is why {@link Client#getAdminContactEmail()} is nullable for
 * a {@link ClientType#VENDOR} row and required here.
 *
 * <h2>The decision fields move together</h2>
 * A PENDING row has no reviewer, no reviewed-at and no approved client; an
 * APPROVED row must name the {@code clients} row it created. Both are CHECK
 * constraints in V11__vendors.sql rather than service-code rules, because a
 * half-written update that leaves either state inconsistent is invisible
 * afterwards - "approved" would stop being able to mean "we made them a vendor".
 *
 * <h2>reviewedBy is a super admin, not a user</h2>
 * Reviewing an application is a platform-operator action, and super admins are an
 * entirely separate identity from tenant users (see V1). The column references
 * {@code super_admins}, and is a raw UUID here for the same reason the rest of
 * this class avoids associations: this entity is read outside any tenant context
 * and has no business dragging graphs behind it.
 */
@Entity
@Table(name = "vendor_waitlist_applications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VendorWaitlistApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "business_name", nullable = false)
    private String businessName;

    /** Required - see the class comment. */
    @Column(nullable = false, length = 255)
    private String email;

    @Column(name = "contact_phone", nullable = false, length = 50)
    private String contactPhone;

    /**
     * Nigeria-only, same four columns and the same names as {@link CompanyVendor}
     * and the vendor profile on {@link Client}. Optional: an applicant who leaves
     * the address blank should still reach a reviewer, who can ask for it.
     */
    @Column(name = "address_line1")
    private String addressLine1;

    @Column(name = "address_line2")
    private String addressLine2;

    @Column(length = 100)
    private String city;

    @Column(length = 100)
    private String state;

    /** What the applicant told us about themselves. Free text, deliberately unparsed. */
    @Column(length = 1000)
    private String notes;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VendorWaitlistStatus status = VendorWaitlistStatus.PENDING;

    /**
     * The reviewer's note. Kept for APPROVED as well as REJECTED: "approved, but
     * only for packaging" is a real outcome somebody will need to read back.
     */
    @Column(name = "review_note", length = 500)
    private String reviewNote;

    /** The {@code super_admins} row that decided. Null while PENDING. */
    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    /**
     * The {@link Client} created on approval, with {@link ClientType#VENDOR}. Null
     * until then, and required once the status is APPROVED.
     */
    @Column(name = "approved_client_id")
    private UUID approvedClientId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** True while nobody has decided. The only state a reviewer may act on. */
    public boolean isPending() {
        return status == VendorWaitlistStatus.PENDING;
    }
}
