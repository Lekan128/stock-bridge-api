package com.procurepal_services.stock_bridge_api.entity;

import com.procurepal_services.stock_bridge_api.tenant.TenantAwareEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
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
import org.springframework.data.domain.Persistable;

/**
 * A waybill: stock sent from one of a company's branches to another (V53, MULTI_BRANCH_BUILD_PLAN.md
 * section 5). The stock itself moves on the ledger - a TRANSFER_OUT per line when sent, TRANSFER_IN lots
 * as it is received - and this records what was sent, what has arrived, and who did each.
 *
 * <p>Its id is made on the phone that sends it, so a send resent after a lost reply is recorded once:
 * hence {@link Persistable}, so saving a new one is an insert rather than a look-up-then-merge.
 */
@Entity
@Table(name = "stock_transfers")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class StockTransfer extends TenantAwareEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Per company, in order of sending; the waybill reads WB-{from code}-{number, four digits}. */
    @Column(nullable = false, updatable = false)
    private int number;

    @Column(name = "from_branch_id", nullable = false, updatable = false)
    private UUID fromBranchId;

    @Column(name = "to_branch_id", nullable = false, updatable = false)
    private UUID toBranchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private StockTransferStatus status = StockTransferStatus.ON_THE_WAY;

    @Column(name = "sent_by", updatable = false)
    private UUID sentBy;

    @Column(name = "sent_at", nullable = false, updatable = false)
    private OffsetDateTime sentAt;

    @Column(name = "carried_by_name", length = 120)
    private String carriedByName;

    @Column(name = "carried_by_phone", length = 50)
    private String carriedByPhone;

    @Column(length = 1000)
    private String note;

    @Column(name = "received_by")
    private UUID receivedBy;

    @Column(name = "received_at")
    private OffsetDateTime receivedAt;

    @Column(name = "missing_checked_by")
    private UUID missingCheckedBy;

    @Column(name = "missing_checked_at")
    private OffsetDateTime missingCheckedAt;

    @Column(name = "missing_note", length = 1000)
    private String missingNote;

    @OneToMany(mappedBy = "transfer", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    @Builder.Default
    private List<StockTransferLine> lines = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Transient
    @Builder.Default
    private boolean persisted = false;

    public void addLine(StockTransferLine line) {
        line.setTransfer(this);
        lines.add(line);
    }

    /** Everything sent is received, missing or damaged. */
    public boolean isAccountedFor() {
        return lines.stream().allMatch(line -> line.onTheWay() == 0);
    }

    public boolean hasLosses() {
        return lines.stream().anyMatch(line -> line.getQuantityMissing() + line.getQuantityDamaged() > 0);
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        this.persisted = true;
    }
}
