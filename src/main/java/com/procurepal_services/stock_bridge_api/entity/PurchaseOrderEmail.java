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

/**
 * One attempt to email a purchase order to its supplier (PURCHASE_ORDER_PLAN.md phase 3). A
 * resend is a new row; a bounce updates the row whose {@code providerMessageId} it names.
 */
@Entity
@Table(name = "purchase_order_emails")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class PurchaseOrderEmail extends TenantAwareEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "expected_delivery_id", nullable = false, updatable = false)
    private UUID expectedDeliveryId;

    @Column(name = "revision", nullable = false)
    private int revision;

    @Column(name = "to_address")
    private String toAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private PurchaseOrderEmailStatus status;

    @Column(name = "problem", length = 300)
    private String problem;

    @Column(name = "provider_message_id", length = 100)
    private String providerMessageId;

    @Column(name = "created_by")
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
