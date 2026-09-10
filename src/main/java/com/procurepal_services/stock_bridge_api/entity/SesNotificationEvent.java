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
 * Every SNS delivery to {@code POST /api/webhooks/ses/notifications}, valid or not,
 * stored before it is acted on.
 *
 * <p>Deliberately the same shape as {@link PaymentWebhookEvent} rather than a fresh
 * invention: it is the same problem - an unauthenticated public endpoint receiving
 * at-least-once deliveries from a third party that can change state - and an
 * operator who has debugged one should recognise the other without being told.
 *
 * <h2>The rejected rows are the interesting ones</h2>
 * A message whose signature did not verify is written here with
 * {@code signatureValid = false} and {@code processed = false}, and only then
 * refused. Somebody who has found this URL and is probing it is otherwise entirely
 * invisible; a run of these rows is the alert. {@code signatureValid} is recorded,
 * never assumed - see {@code SnsSignatureVerifier}.
 *
 * <h2>Idempotency</h2>
 * SNS guarantees at-least-once delivery and retries anything not answered 2xx, so
 * the same notification WILL arrive more than once. {@link #messageId} is SNS's own
 * {@code MessageId}, stable across retries, and a partial unique index in the
 * migration enforces that at most one row per id may ever be {@code processed}.
 * Retries are still stored, marked unprocessed with a note - losing the record of a
 * retry would hide exactly the misbehaviour you want to see.
 *
 * <h2>Two type columns, not one</h2>
 * {@link #messageType} is the SNS envelope's {@code Type} ({@code Notification},
 * {@code SubscriptionConfirmation}, {@code UnsubscribeConfirmation});
 * {@link #notificationType} is the SES event nested inside a Notification
 * ({@code Bounce}, {@code Complaint}, ...). They answer different operational
 * questions - "is our topic subscription healthy" and "what is our bounce rate" -
 * and both get asked.
 *
 * <h2>Not tenant-scoped</h2>
 * Same reason as {@link PaymentWebhookEvent}: the endpoint is public, so
 * {@code TenantContext} is empty and the Hibernate tenant filter is off for the
 * whole request. A {@code client_id} here would have to be invented, and it is not
 * a fact the notification contains.
 */
@Entity
@Table(name = "ses_notification_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SesNotificationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * SNS's {@code MessageId}. Nullable because a malformed or truncated body has
     * none - and that body is the one most worth keeping. Postgres treats NULLs as
     * distinct in a unique index, so any number of unidentifiable rows coexist.
     */
    @Column(name = "message_id", length = 200)
    private String messageId;

    @Column(name = "message_type", length = 60)
    private String messageType;

    @Column(name = "notification_type", length = 60)
    private String notificationType;

    /**
     * {@code Permanent} / {@code Transient} / {@code Undetermined} for a bounce, the
     * complaint feedback type for a complaint. Lifted out of the payload into its own
     * column because it is the field that decides whether anything was suppressed,
     * and "show me every permanent bounce this week" must not need a JSONB scan.
     */
    @Column(name = "sub_type", length = 60)
    private String subType;

    @Column(name = "signature_valid", nullable = false)
    private boolean signatureValid;

    @Column(nullable = false)
    private boolean processed;

    /** Why it was, or was not, acted on. The first line anyone reads. */
    @Column(name = "processing_note", length = 500)
    private String processingNote;

    /** The SNS envelope exactly as received; an unparseable body is wrapped, never dropped. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    /** A received notification is a historical fact; there is no updated_at. */
    @CreationTimestamp
    @Column(name = "received_at", nullable = false, updatable = false)
    private OffsetDateTime receivedAt;
}
