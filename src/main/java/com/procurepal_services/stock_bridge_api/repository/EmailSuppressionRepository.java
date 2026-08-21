package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.EmailSuppression;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The suppression list.
 *
 * <p>Not tenant-scoped: {@link EmailSuppression} is not a {@code TenantAwareEntity},
 * it is written from an unauthenticated SNS webhook thread with no
 * {@code TenantContext}, and it is read on every send path regardless of whose
 * request triggered it. Whether an address may be mailed is a fact about the
 * address, not about the tenant asking.
 *
 * <h2>What is deliberately NOT here</h2>
 * The two cross-tenant {@code UPDATE}s on {@code users} that accompany a
 * suppression - clearing {@code is_email_verified} after a permanent bounce and
 * {@code receive_promotional_email} after a complaint - live in
 * {@code EmailSuppressionService} as {@code EntityManager} native statements, not on
 * this interface. That follows the precedent {@code UnsubscribeService} set for
 * exactly this shape of write; read its javadoc for the full argument. The short
 * version is that a {@code @Modifying} bulk update on a shared repository interface
 * puts its transaction boundary somewhere other than the code it protects, and puts
 * an unscoped cross-tenant statement one autocomplete away from code that believes
 * it is working on a single tenant.
 *
 * <p>Every address passed to any method here must already be lowercased and
 * trimmed. Nothing normalises on the caller's behalf, deliberately: the column
 * carries a database CHECK that it is stored normalised, so a caller that skipped
 * the step gets a constraint violation on write rather than a row that silently
 * matches no read - which is the one failure mode in this table that would look
 * exactly like success.
 */
public interface EmailSuppressionRepository extends JpaRepository<EmailSuppression, UUID> {

    /**
     * The hot-path read, called once per recipient per message by
     * {@code EmailEligibility}. Served by the unique index behind
     * {@code uq_email_suppressions_address}; the predicate is a plain equality
     * because the stored value is already the lookup key (see the migration for why
     * there is deliberately no {@code lower()} index on this table, unlike V8's on
     * {@code users} and {@code clients}).
     */
    boolean existsByAddress(String address);

    /**
     * "Why is this address suppressed" - reason, diagnostic, and the notification it
     * came from. Also the read half of the upsert: a second bounce for an address
     * already listed updates the existing row rather than colliding with the unique
     * constraint.
     */
    Optional<EmailSuppression> findByAddress(String address);

    /**
     * Lifting a suppression. Returns how many rows went, so a caller can tell
     * "un-suppressed" from "was not suppressed on the first place" - the two need
     * different words in a log line, and an operator asking why nothing changed
     * deserves the second one.
     */
    long deleteByAddress(String address);
}
