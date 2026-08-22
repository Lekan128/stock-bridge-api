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
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * One entry in a BUYING company's own list of who it buys from.
 *
 * <h2>This is not a seller</h2>
 * Two things in this product are called "vendor" and they are not the same. A
 * seller on the marketplace is a {@link Client} with
 * {@link ClientType#VENDOR} - it has an account, a catalogue and an order queue.
 * This class is bookkeeping: a row in one company's private supplier directory,
 * which may point at such a seller ({@link CompanyVendorKind#VERIFIED}) or at
 * somebody with no account anywhere ({@link CompanyVendorKind#EXTERNAL}). Nothing
 * here grants anybody the ability to sell. The V11__vendors.sql header states the
 * distinction in full; read it before writing code that touches both.
 *
 * <h2>Tenant-scoped, and why that is the right scope</h2>
 * Extends {@link TenantAwareEntity}, so client_id is the BUYER that owns the
 * directory and is assigned from {@code TenantContext} on persist - never by a
 * caller. Two companies that buy from the same platform vendor get two
 * independent rows and neither can see the other's, which is correct: the row
 * carries that company's own relationship with the supplier (its notes, its
 * purchase history), not a shared fact about the supplier.
 *
 * <h2>platformClientId is a raw UUID, not an association</h2>
 * It points at the SELLER's clients row, which belongs to a different tenant.
 * {@link Client} is not itself tenant-scoped so a mapped association would load,
 * but keeping it a plain id matches how {@link Order} treats every cross-tenant
 * reference and keeps the direction of the pointer obvious at every call site.
 * Load the target explicitly through {@code ClientRepository} when the vendor's
 * live name, phone or logo is needed.
 *
 * <h2>Why name is stored even for VERIFIED rows</h2>
 * A VERIFIED row could have read its name through {@code platformClientId}. It
 * does not, because the directory list is sorted, searched and paged by name, and
 * a nullable column plus a cross-tenant join turns every one of those into a
 * COALESCE over an OUTER JOIN that no index can serve.
 *
 * <p>The cost: if a platform vendor renames itself, every VERIFIED row naming it
 * goes stale until something refreshes it. Keeping them in step belongs to
 * whatever handles a vendor profile update - it must rewrite {@code name} on rows
 * pointing at that vendor. It is explicitly NOT the buyer's job, because a
 * VERIFIED row is not editable by the company that owns it.
 *
 * <h2>Coherence between the two kinds is a database concern</h2>
 * VERIFIED requires {@code platformClientId}; EXTERNAL forbids it and requires
 * {@code contactPhone}. Both conditions are row-local, so both are CHECK
 * constraints rather than service-code rules - see V11__vendors.sql. Constructing
 * an incoherent instance here fails at flush with a
 * {@code DataIntegrityViolationException}, by design: the failure modes if it did
 * not are silent ones.
 */
@Entity
@Table(name = "company_vendors")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class CompanyVendor extends TenantAwareEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "vendor_kind", nullable = false, length = 20)
    private CompanyVendorKind vendorKind;

    /**
     * The selling {@link Client} this entry refers to. Required for VERIFIED, and
     * must be null for EXTERNAL. See the class comment for why it is a raw UUID.
     */
    @Column(name = "platform_client_id")
    private UUID platformClientId;

    /** Required for both kinds. Snapshotted for VERIFIED - see the class comment. */
    @Column(nullable = false)
    private String name;

    /**
     * Required for EXTERNAL (a hand-typed supplier with no phone number is not a
     * record of anything), optional for VERIFIED where the authoritative number is
     * the seller's own {@link Client#getPhone()}.
     */
    @Column(name = "contact_phone", length = 50)
    private String contactPhone;

    @Column(length = 255)
    private String email;

    /**
     * Nigeria-only, same four columns and the same names as
     * {@link DeliveryAddress} and the vendor profile on {@link Client}, so one set
     * of frontend address controls and one state list serve all three. Optional
     * for both kinds.
     */
    @Column(name = "address_line1")
    private String addressLine1;

    @Column(name = "address_line2")
    private String addressLine2;

    @Column(length = 100)
    private String city;

    @Column(length = 100)
    private String state;

    /** The company's own note about this supplier. Never shown to the vendor. */
    @Column(length = 1000)
    private String notes;

    /**
     * Soft delete, matching {@link DeliveryAddress}: products and purchase history
     * reference these rows, so a removed supplier is deactivated rather than
     * deleted.
     */
    @Column(name = "is_active", nullable = false)
    private boolean active;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * Whether the owning company may edit this row's own fields. A VERIFIED entry
     * was written by the platform on the strength of a real purchase, so the buyer
     * may deactivate it but not rewrite what it says.
     *
     * <p>Convenience only: the endpoints that enforce this belong to a later
     * module and must still check it themselves. It exists so the rule has one
     * name rather than an {@code == EXTERNAL} scattered across them.
     */
    public boolean isEditableByOwningCompany() {
        return vendorKind != null && vendorKind.isCompanyEditable();
    }
}
