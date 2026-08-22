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

/**
 * A single-use, expiring proof that somebody can read the address it was mailed
 * to. Redeeming one flips {@code users.is_email_verified}; see
 * {@code V9__email_verification_tokens.sql} for the full schema rationale and
 * {@code EmailVerificationService} for the policy.
 *
 * <h2>Modelled on RefreshToken, not on User</h2>
 * Same class of thing as {@link RefreshToken}: an opaque random value handed to a
 * client, of which only the SHA-256 hash is stored, looked up by that hash and
 * never read back out. The fields mirror it deliberately - a reader who knows one
 * knows the other, and the two invalidation paths below are the only place they
 * diverge.
 *
 * <h2>Why this is NOT a TenantAwareEntity, unlike User</h2>
 * The endpoint that redeems a token is unauthenticated, so {@code TenantContext}
 * is empty and Hibernate's tenant filter is disabled for the whole request. A
 * tenant-scoped entity is unreadable in that situation - by construction, which is
 * the point of {@code TenantScopedRepository} - so scoping this one would break the
 * only flow it exists for.
 *
 * <p>That is not a hole. The tenant is reachable through {@link #userId}, and the
 * raw token is the entire authorisation: holding it permits exactly one change, to
 * exactly one users row, named by this row. Nothing lists or aggregates these per
 * tenant, so there is no query for a filter to protect.
 *
 * <h2>The two ways a token dies without being clicked</h2>
 * {@link #consumedAt} means it worked. {@link #supersededAt} means a newer token
 * replaced it. They are separate columns rather than one flag because the support
 * answer differs: consumed means the account is already verified and the user
 * should just sign in, superseded means a newer email is in their inbox.
 */
@Entity
@Table(name = "email_verification_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailVerificationToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * The users row this token verifies. A plain UUID rather than a
     * {@code @ManyToOne User}, on purpose: {@code User} is a tenant-filtered
     * entity, and a managed association would be resolved by Hibernate under
     * whatever filter state the current thread happens to have - which on the
     * unauthenticated redemption path is "disabled" and on an authenticated resend
     * is "some tenant". The service loads the user explicitly through
     * {@code findById} instead, which bypasses the filter deterministically rather
     * than incidentally. The database still holds a real FK; see the migration.
     */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /**
     * The address this token was mailed to, lowercased. Frozen at issue time and
     * compared against the user's current address at redemption, so a link cannot
     * be used to verify an address it was never sent to. See the migration for the
     * attack this closes.
     */
    @Column(name = "email_address", nullable = false, length = 255, updatable = false)
    private String emailAddress;

    /** SHA-256 of the raw token. The raw value exists only in the recipient's inbox. */
    @Column(name = "token_hash", nullable = false, unique = true, updatable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private OffsetDateTime expiresAt;

    /** Set once, when the link is successfully clicked. Non-null means "already used". */
    @Column(name = "consumed_at")
    private OffsetDateTime consumedAt;

    /** Set when a newer token is issued for the same user. Non-null means "retired unused". */
    @Column(name = "superseded_at")
    private OffsetDateTime supersededAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * The whole redemption precondition in one place, so the controller path, the
     * tests and any future caller cannot each spell it slightly differently.
     *
     * <p>Deliberately takes {@code now} rather than reading the clock itself: the
     * service checks several things against one instant, and a method that sampled
     * its own clock would make "expired" depend on where in the method it was
     * asked. It also makes the boundary testable without sleeping.
     */
    public boolean isRedeemableAt(OffsetDateTime now) {
        return consumedAt == null && supersededAt == null && expiresAt.isAfter(now);
    }
}
