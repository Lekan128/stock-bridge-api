package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.EmailVerificationToken;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Plain {@link JpaRepository}, not {@link TenantScopedRepository}, and that choice
 * is the interesting thing about this file.
 *
 * <h2>Why derived queries are safe here and are not on User</h2>
 * {@code UserRepository} has to drop to native SQL for its address lookups because
 * {@code User} is a {@code TenantAwareEntity}: a derived or JPQL query over it
 * carries Hibernate's tenant filter whenever that filter happens to be enabled on
 * the session and not when it happens not to be, so the same question returns
 * different answers depending on who is asking.
 *
 * <p>{@link EmailVerificationToken} is not tenant-aware, so no filter is ever
 * applied to it and there is nothing to be inconsistent about. Every method below
 * therefore reads across all tenants, always, identically - which is exactly what
 * the unauthenticated redemption endpoint needs, since it has no tenant to be
 * scoped to and only a bearer secret to identify the row with.
 *
 * <p>The tenancy question does not disappear, it moves: the row names a
 * {@code user_id}, and the service resolves that user through
 * {@code UserRepository.findById}, which goes through {@code EntityManager.find}
 * and so is equally filter-independent. Both halves of the lookup are deliberate
 * about this rather than accidentally correct.
 */
public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, UUID> {

    /**
     * The only way a token is ever found. The caller hashes the raw value it was
     * given and probes the unique index; nothing reads a token back out, which is
     * what lets the raw value stay out of the database entirely.
     */
    Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

    /**
     * This user's tokens that are still live - not clicked, not already retired.
     * Read on the issue path so they can be superseded, and returned as entities
     * rather than updated in bulk so the timestamps go through the same managed
     * write as everything else.
     *
     * <p>Note this deliberately does NOT filter on {@code expiresAt}: an expired
     * token is already unredeemable, and stamping it superseded as well would
     * overwrite the more informative of the two states with the less informative
     * one. Expiry is checked at redemption, not here.
     */
    List<EmailVerificationToken> findAllByUserIdAndConsumedAtIsNullAndSupersededAtIsNull(UUID userId);

    /**
     * How many tokens this user has been issued since a given instant.
     *
     * <p>Not currently the rate limiter - that is in-process, see
     * {@code EmailVerificationRateLimiter} for why - but it is the query a
     * database-backed limiter would need, and it is here because it is also the
     * one an operator wants when answering "is this account being used to pump
     * mail at an address". Keeping it next to the limiter's own doc makes the
     * upgrade path a two-line change rather than a redesign.
     */
    long countByUserIdAndCreatedAtAfter(UUID userId, OffsetDateTime since);
}
