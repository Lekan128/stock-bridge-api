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

/**
 * One address that must not be mailed, for any reason, by any kind of message.
 *
 * <h2>Why this is keyed by the address and not by a user or a client</h2>
 * This table exists to close the gap {@code V8__email_eligibility.sql} explicitly
 * left open. The verified and consent flags live on {@code users}, but most mail
 * this application sends goes to {@code clients.admin_contact_email} - a shared
 * finance or operations inbox that no user has ever logged in as - and some goes to
 * {@code app.email.operator-address}, which is a configuration value with no row in
 * any table at all. When SES tells us one of those is permanently undeliverable
 * there is no user row to demote, so a design built on user flags would report
 * success and keep mailing a dead address forever.
 *
 * <p>The address string is the only thing all three sources have in common, and it
 * is also the only identifier SES ever gives us - a bounce notification names an
 * email address, never an id of ours. Keying on exactly what the provider says is
 * what guarantees a lookup cannot fail to find the row it should have found.
 *
 * <h2>No foreign key, and that is the requirement rather than an omission</h2>
 * A suppression is a fact about an inbox, not about an account. It must be
 * recordable for an address that belongs to no row we own, and it must survive the
 * deletion of the user or client that happened to hold it. An FK to either would
 * forbid both.
 *
 * <h2>The address is stored already normalised</h2>
 * Lowercased and trimmed by the writer, and a database CHECK enforces it (see the
 * migration). This is the one field in the email package where a normalisation slip
 * would be silent rather than loud: an unnormalised row matches no read, so the
 * suppression would appear to have been applied and would in fact do nothing.
 *
 * <h2>Not tenant-scoped</h2>
 * Deliberately not a {@code TenantAwareEntity}. It is written from an
 * unauthenticated SNS webhook thread where {@code TenantContext} is empty - a
 * tenant-aware entity's {@code @PrePersist} would refuse to persist at all - and
 * read from every send path regardless of whose request triggered it. Whether an
 * address may be mailed is a fact about the address, not about the tenant asking.
 */
@Entity
@Table(name = "email_suppressions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailSuppression {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Lowercased, trimmed. 320 rather than the 255 of {@code users.username} and
     * {@code clients.admin_contact_email}: that is the RFC 5321 maximum, and a
     * bounce for an address longer than anything we could have stored is still
     * evidence about an inbox and still has to be recordable.
     */
    @Column(nullable = false, length = 320, updatable = false)
    private String address;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private EmailSuppressionReason reason;

    /**
     * The SNS {@code MessageId} (or SES {@code feedbackId}) of the notification that
     * caused this, so {@code ses_notification_events} can be joined to for the full
     * payload. By value rather than by FK on purpose - the event log grows with
     * provider traffic and will eventually be pruned, and pruning it must not
     * cascade into deleting a live suppression.
     *
     * <p>Null for a {@link EmailSuppressionReason#MANUAL} entry, which is how
     * operator-made rows are told apart from provider-driven ones.
     */
    @Column(name = "source_message_id", length = 200)
    private String sourceMessageId;

    /**
     * SES's {@code diagnosticCode} - the remote SMTP server's own words. The field
     * that distinguishes "this mailbox never existed" from "this domain has stopped
     * accepting mail from us", which are the same bounce and completely different
     * problems.
     */
    @Column(columnDefinition = "text")
    private String diagnostic;

    /**
     * No {@code updated_at}: a suppression is not edited. A later, different
     * notification about the same address overwrites {@code reason},
     * {@code sourceMessageId} and {@code diagnostic} in place (there is one row per
     * address by constraint), and {@code createdAt} deliberately keeps saying when
     * the address was FIRST suppressed - "how long has this been dead" is the
     * question that gets asked, not "when did we last hear about it", which the
     * event log answers better anyway.
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
