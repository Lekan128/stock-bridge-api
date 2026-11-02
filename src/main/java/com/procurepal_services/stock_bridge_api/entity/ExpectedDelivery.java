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

    public boolean isPurchaseOrder() {
        return poNumber != null;
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
