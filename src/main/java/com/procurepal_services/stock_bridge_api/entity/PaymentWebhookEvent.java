package com.procurepal_services.stock_bridge_api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Every provider callback we receive, valid or not, stored before it is acted on.
 * A rejected signature or a replayed callback is otherwise completely invisible,
 * and "we sent you that webhook" is a conversation that needs evidence.
 *
 * There is no FK to Payment on purpose: a malformed or spoofed callback may
 * reference nothing we know about, and it still has to be recordable.
 *
 * Not tenant-scoped - the webhook endpoint is public and has no TenantContext.
 *
 * signatureValid is recorded, never assumed: an invalid-signature callback is
 * written with FALSE and must not be processed.
 */
@Entity
@Table(name = "payment_webhook_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentWebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, length = 30)
    private String provider;

    @Column(name = "event_type", length = 60)
    private String eventType;

    @Column(name = "transaction_reference", length = 100)
    private String transactionReference;

    @Column(name = "payment_reference", length = 100)
    private String paymentReference;

    @Column(name = "signature_valid", nullable = false)
    private boolean signatureValid;

    @Column(nullable = false)
    private boolean processed;

    /** Why it was (or was not) processed - the first thing anyone debugging a stranded order reads. */
    @Column(name = "processing_note", length = 500)
    private String processingNote;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    /**
     * There is no updated_at column on this table - a received callback is a
     * historical fact. `processed`/`processing_note` are the only mutable bits,
     * and they are set once, in the same transaction that handles the callback.
     */
    @CreationTimestamp
    @Column(name = "received_at", nullable = false, updatable = false)
    private OffsetDateTime receivedAt;
}
