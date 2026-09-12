package com.procurepal_services.stock_bridge_api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * A tenant. slug is the human-readable identifier a tenant's users log in
 * with, distinct from the surrogate id used for foreign keys.
 *
 * <h2>Two kinds of tenant, one table</h2>
 * Since V11 a client is either a buying {@link ClientType#COMPANY} - the ordinary
 * tenant this application started with - or a selling
 * {@link ClientType#VENDOR}. They share this table for the same reason the
 * platform owner does: everything a vendor needs (users, products, stock,
 * analytics, addresses) is already tenant-scoped, and a separate entity would
 * mean duplicating every one of those relationships.
 *
 * <p>{@link #clientType} and {@link #platformOwner} are ORTHOGONAL. The flag does
 * not say what an account may do; it names the single account that owns the
 * marketplace. ProcurePal is a COMPANY (it buys, it has staff) that also happens
 * to be the platform owner and a seller. See {@link ClientType}.
 *
 * <p>A VENDOR client is a seller. It is NOT a {@link CompanyVendor}, which is a
 * row in some buying company's private supplier directory. The two concepts share
 * a word and nothing else.
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

    /**
     * The company's contact of record - where account correspondence, order
     * receipts and payment mail go (see EmailRecipients.forClient).
     *
     * <p>Nullable since V11, for VENDOR rows only. A vendor's email is required
     * when they came through the waitlist (it was the only identifier we had for
     * them) and optional when a super admin adds them directly, having met them
     * in person or over the phone. A database CHECK still requires it for every
     * COMPANY, so the invariant this column has always carried holds exactly
     * where it still applies. Writing a synthetic placeholder instead was
     * rejected: it would put an address we invented into the column the mailer
     * reads, and we would then send real mail to it.
     */
    @Column(name = "admin_contact_email")
    private String adminContactEmail;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    /**
     * The account's contact number - and, for a VENDOR, the vendor's contact
     * number. Deliberately not duplicated as a vendor-specific column: it is the
     * same fact, and a second column could immediately disagree with this one.
     */
    @Column(length = 50)
    private String phone;

    /**
     * Buying company or marketplace seller. Defaults to COMPANY, matching the
     * column default that makes V11 backward compatible: every row that existed
     * before it is a buying company and keeps behaving exactly as it did.
     *
     * <p>Read it through {@code VendorGuard} rather than inline where the answer
     * decides whether to serve a request, so the 403 behaviour for vendor
     * surfaces is defined in one place - the same arrangement
     * {@code PlatformOwnerGuard} provides for {@link #platformOwner}.
     */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "client_type", nullable = false, length = 20)
    private ClientType clientType = ClientType.COMPANY;

    /**
     * The vendor's profile picture / logo. One of only two things a browsing
     * company sees about a vendor - the other is {@link #name} - alongside their
     * products. Everything else on this row is operator-facing.
     *
     * <p>TEXT in the schema, matching products.image_url: these are S3 URLs whose
     * length is not ours to bound.
     */
    @Column(name = "logo_url", columnDefinition = "TEXT")
    private String logoUrl;

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

    /**
     * The business's registered address - a vendor's, in practice, since a buying
     * company's addresses live in delivery_addresses.
     *
     * <p>Structured and named to match {@link DeliveryAddress} column for column,
     * so the same frontend address controls and the same Nigerian-state list serve
     * both; Nigeria-only, so there is no country field. Deliberately NOT a
     * reference to a DeliveryAddress row: that table answers "where do we ship
     * goods to", carries required label/contact fields a registered address does
     * not have, and is soft-deletable, whereas an address of record has to exist
     * from the moment the account does. Vendors still use delivery_addresses as-is
     * for their PICKUP addresses - a vendor is a client, so that table is already
     * scoped to them.
     */
    @Column(name = "address_line1")
    private String addressLine1;

    @Column(name = "address_line2")
    private String addressLine2;

    @Column(length = 100)
    private String city;

    @Column(length = 100)
    private String state;

    /**
     * The platform's take on this vendor's sales, as a fraction - {@code 0.0750}
     * is 7.5%. Exact ({@code NUMERIC(5,4)}), because floating point has no business
     * near money.
     *
     * <h2>Null is not zero</h2>
     * Null means "no vendor-specific rate has been agreed, use the platform
     * default". A zero rate is a different and equally real arrangement - a vendor
     * onboarded commission-free as an incentive - and collapsing the two would make
     * "we agreed they pay nothing" indistinguishable from "nobody has decided yet".
     * Every read must branch on null rather than calling
     * {@code BigDecimal.ZERO} equivalent. Where the platform default itself lives is
     * the payout module's decision, not this class's.
     *
     * <h2>This is the CURRENT agreement, never the historical one</h2>
     * What a given sale actually earned is frozen on
     * {@link OrderItem#getCommissionRate()} at the moment the line was sold.
     * Renegotiating this field must never change what the platform earned on orders
     * already shipped - the same discipline the order-line price snapshots exist
     * for. Nothing computes commission yet; the accrual (on delivery, not at
     * checkout - see VENDOR_RESEARCH.md Section C item 2), the ledger and the payout
     * batch are a later module.
     *
     * <p>Meaningless and null on a COMPANY row, like {@link #logoUrl}. No constraint
     * ties it to {@link #clientType}: agreeing a rate with a business before
     * flipping them to VENDOR is an ordinary sequence.
     */
    @Column(name = "commission_rate", precision = 5, scale = 4)
    private BigDecimal commissionRate;

    /**
     * Where ProcurePaddy pays this seller out, and the registration number behind
     * the business. All four are optional and all four are a VENDOR's fields - like
     * {@link #logoUrl} and {@link #commissionRate} they are meaningless on a COMPANY
     * row, and for the same reason no constraint ties them to {@link #clientType}:
     * recording a business's bank details before flipping them to VENDOR is an
     * ordinary sequence.
     *
     * <h2>Not the same as a company_vendors row's bank details</h2>
     * A {@link CompanyVendor} carries its own independent set. These are OUR banking
     * relationship with the seller; those are one buyer's record of how they pay a
     * supplier off-platform. They may legitimately differ and neither is sourced
     * from the other - see V28__vendor_bank_details_and_cac.sql.
     *
     * <h2>Deliberately unvalidated beyond length</h2>
     * A Nigerian NUBAN is ten digits, but these fields are filled in progressively
     * by an ops user working from whatever the vendor sent, so a half-entered number
     * must be storable. Nothing pays out automatically off these columns; a human
     * reads them before money moves.
     */
    @Column(name = "bank_name")
    private String bankName;

    @Column(name = "bank_account_number", length = 50)
    private String bankAccountNumber;

    @Column(name = "bank_account_name")
    private String bankAccountName;

    /** Corporate Affairs Commission registration number, as written ("RC 123456"). See {@link #bankName}. */
    @Column(name = "cac_number", length = 50)
    private String cacNumber;

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

    /**
     * A marketplace seller account. Convenience over the enum constant, in the
     * same spirit as {@link #isPayOnDeliveryAllowed()}.
     *
     * <p>For authorization, go through {@code VendorGuard} instead: this answers
     * "what kind of account is this", not "may this request proceed", and the
     * two must not drift apart across call sites.
     */
    public boolean isVendor() {
        return clientType != null && clientType.isVendor();
    }

    /**
     * May this client sell on the marketplace at all - i.e. own listed products
     * and appear as {@code orders.seller_client_id}?
     *
     * <p>True for a vendor and for the platform owner. ProcurePal is a COMPANY
     * that also sells, which is exactly why sell-side checks ask this question
     * rather than {@code isVendor()}: writing the latter is the easy way to lock
     * ProcurePal out of its own marketplace.
     */
    public boolean canSell() {
        return isVendor() || platformOwner;
    }
}
