package com.procurepal_services.stock_bridge_api.entity;

import com.procurepal_services.stock_bridge_api.tenant.TenantAwareEntity;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * What a company has ordered from an off-platform supplier and is still waiting for
 * (BULK_IMPORT_CX_PLAN.md task 3.1).
 *
 * <h2>Purchase orders are these, with a number</h2>
 * A purchase order (PURCHASE_ORDER_PLAN.md) is not a second table: it is one of these rows that
 * also carries {@link #poNumber}, a {@link #revision} and a frozen {@link #snapshot} of what the
 * supplier was shown. A row recorded the old way - typed from a phone call, never sent - leaves
 * those empty and behaves exactly as before. One list of what was ordered, one "coming" figure,
 * one receive path. Approval is deliberately not here; it is a later addition.
 *
 * <h2>Why it leaves {@code Product.incoming_quantity} alone</h2>
 * That column means "paid for through the marketplace and on its way". It is maintained under a
 * row lock, beside a real ledger, and the marketplace treats it as settled. What is recorded here
 * is a promise someone made on the phone - it may arrive short, late, or never. Adding the two
 * together would put a guess inside a number other code trusts, so a product's "coming" figure is
 * computed from open lines and reported separately.
 */
@Entity
@Table(name = "expected_deliveries")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class ExpectedDelivery extends TenantAwareEntity {

    public static final int REFERENCE_MAX_LENGTH = 200;
    public static final int NOTE_MAX_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Who it is coming from. Nullable, and {@code ON DELETE SET NULL}: a supplier removed from the
     * directory must not take the record of what they owe you with it.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id")
    private CompanyVendor vendor;

    /** When they said it would come. Nullable - plenty of suppliers never say. */
    @Column(name = "expected_date")
    private LocalDate expectedDate;

    /** Their invoice or waybill number, if it is known before the goods arrive. */
    @Column(length = REFERENCE_MAX_LENGTH)
    private String reference;

    @Column(length = NOTE_MAX_LENGTH)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private ExpectedDeliveryStatus status = ExpectedDeliveryStatus.OPEN;

    @Column(name = "created_by")
    private UUID createdBy;

    /**
     * The branch it is going to (V52) - whose "Needs you today" shows it. Receiving it is a stock-in,
     * which names its own branch; this does not decide where the stock lands.
     */
    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    /** "PO-0007"; null for an expectation that was only written down, never sent. */
    @Column(name = "po_number", length = 20)
    private String poNumber;

    /** The 7 in PO-0007 - per company, allocated by {@code PurchaseOrderNumbers}. */
    @Column(name = "po_sequence")
    private Integer poSequence;

    /** 1 for the order as first made; a change to a sent order makes the next one. */
    @Column(name = "revision", nullable = false)
    @Builder.Default
    private int revision = 1;

    /**
     * What the PDF and the public page show, as JSON ({@code PurchaseOrderSnapshot}), written when
     * the order is made. The PDF is built from this on the fly and never stored.
     */
    @Column(name = "snapshot", columnDefinition = "TEXT")
    private String snapshot;

    /**
     * What the supplier charges to bring it, for the whole order (V77). Null is "not said", zero is
     * free delivery. Added to the order's total.
     */
    @Column(name = "delivery_charge", precision = 18, scale = 2)
    private BigDecimal deliveryCharge;

    /**
     * The unguessable part of the public link a supplier opens without logging in. Set once, when
     * the order is made; never changes, so a link already sent keeps working through revisions.
     */
    @Column(name = "share_token", length = 64)
    private String shareToken;

    /** When the buyer last opened WhatsApp for this order. All we can know - never "delivered". */
    @Column(name = "whatsapp_opened_at")
    private OffsetDateTime whatsappOpenedAt;

    /** When the buyer last copied the message. */
    @Column(name = "message_copied_at")
    private OffsetDateTime messageCopiedAt;

    /**
     * The revision the supplier has been given (V76), or null when they have none. Below
     * {@link #revision} means a change has not gone out yet.
     */
    @Column(name = "sent_revision")
    private Integer sentRevision;

    /** When the supplier was last given it. */
    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    /** How: EMAIL, WHATSAPP, MESSAGE (copied) or PHONE (ordered by phone, nothing sent). */
    @Column(name = "sent_via", length = 16)
    private String sentVia;

    /**
     * Approval (V78, plan section 11.5): null when none is involved; WAITING, CHANGE_ASKED or APPROVED
     * for an order made by someone who can only create one.
     */
    @Column(name = "approval", length = 16)
    private String approval;

    @Column(name = "approval_asked_by")
    private UUID approvalAskedBy;

    @Column(name = "approval_asked_at")
    private OffsetDateTime approvalAskedAt;

    /** What the person asking wrote for the approver ("Tony is ₦500 up on rice this week"). */
    @Column(name = "approval_note", length = 500)
    private String approvalNote;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @Column(name = "change_asked_by")
    private UUID changeAskedBy;

    @Column(name = "change_asked_at")
    private OffsetDateTime changeAskedAt;

    /** What the approver asked to be changed. */
    @Column(name = "change_note", length = 500)
    private String changeNote;

    public static final String APPROVAL_WAITING = "WAITING";
    public static final String APPROVAL_CHANGE_ASKED = "CHANGE_ASKED";
    public static final String APPROVAL_APPROVED = "APPROVED";

    /** Waiting for an approver, or back with whoever made it for a change: either way, not to be sent yet. */
    public boolean isAwaitingApproval() {
        return APPROVAL_WAITING.equals(approval) || APPROVAL_CHANGE_ASKED.equals(approval);
    }

    public boolean isPurchaseOrder() {
        return poNumber != null;
    }

    /** Write down that the supplier now has the current revision, and how they got it. */
    public void markSent(String via, OffsetDateTime at) {
        this.sentRevision = revision;
        this.sentAt = at;
        this.sentVia = via;
    }

    /** A purchase order whose current revision the supplier doesn't have yet. */
    public boolean isWaitingToBeSent() {
        return poNumber != null && !isAwaitingApproval() && (sentRevision == null || sentRevision < revision);
    }

    @OneToMany(mappedBy = "expectedDelivery", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC")
    @Builder.Default
    private List<ExpectedDeliveryLine> lines = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public void addLine(ExpectedDeliveryLine line) {
        line.setExpectedDelivery(this);
        lines.add(line);
    }

    /** True once every line has had at least what was expected of it received. */
    public boolean isFullyReceived() {
        return !lines.isEmpty() && lines.stream().allMatch(ExpectedDeliveryLine::isSettled);
    }
}
