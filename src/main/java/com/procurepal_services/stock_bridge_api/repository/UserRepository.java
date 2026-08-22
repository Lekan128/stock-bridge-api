package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends TenantScopedRepository<User, UUID> {

    Optional<User> findByClientIdAndUsername(UUID clientId, String username);

    long countByClientIdAndRole_NameAndActiveTrue(UUID clientId, String roleName);

    long countByClientId(UUID clientId);

    long countByClientIdAndActiveTrue(UUID clientId);

    // ========================================================================
    // Email eligibility lookups. Read by EmailEligibility only; see that class
    // for what the answers mean.
    //
    // WHY THESE ARE NATIVE QUERIES AND NOT DERIVED ONES
    // Not for speed, and not to write clever SQL - for determinism about tenancy.
    // User is a TenantAwareEntity, so a derived or JPQL query carries Hibernate's
    // tenant filter whenever that filter happens to be enabled on the session, and
    // does not when it happens not to be (an unauthenticated webhook thread, a
    // scheduled sweep). That makes the SAME question return different answers
    // depending on who is asking, which for eligibility is intolerable: whether an
    // address may be mailed is a fact about the address, not about the tenant of
    // the request that triggered the send. Native queries are not rewritten by the
    // filter, so these read across all tenants, always, identically.
    //
    // That is deliberate cross-tenant reading, so it is worth being precise about
    // what it does and does not expose. It returns counts and nothing else - no
    // ids, no names, no rows. A caller learns "somebody, somewhere, holds this
    // address and has verified it", which is exactly the fact needed to decide
    // whether to put that address in a To: header and no more. TenantScopeExecutor
    // is the wrong tool here for a simpler reason: it re-points the filter at ONE
    // named client, and there is no client to name - the input is a bare string
    // that may belong to any tenant or to none.
    //
    // WHY username IS MATCHED AS WELL AS email
    // users.email is nullable and often empty, while a tenant's first user signs
    // up with an email address as their USERNAME (ClientSignupService). For those
    // rows the username is the only address there is, and matching on email alone
    // would report "no such user" for the account holder of every company on the
    // platform. EmailRecipients.forUser falls back identically.
    //
    // Callers must pass an already-lowercased, already-trimmed address; these do
    // not normalise, because doing it per-query would hide the fact that the
    // caller has to normalise once for the clients lookup too.
    // ========================================================================

    /** How many user rows claim this address at all, verified or not. */
    @Query(
            value = "SELECT count(*) FROM users WHERE lower(email) = :address OR lower(username) = :address",
            nativeQuery = true)
    long countMatchingEmailAddress(@Param("address") String address);

    /** How many of those have verified it. */
    @Query(
            value = "SELECT count(*) FROM users WHERE is_email_verified = TRUE "
                    + "AND (lower(email) = :address OR lower(username) = :address)",
            nativeQuery = true)
    long countVerifiedMatchingEmailAddress(@Param("address") String address);

    /**
     * How many of those have unsubscribed from marketing. Counted as opt-OUTS
     * rather than opt-ins because that is the question with the safe answer: one
     * person who unsubscribed must silence the inbox even if another user row
     * sharing that address never did. Zero is the only value that permits a send.
     */
    @Query(
            value = "SELECT count(*) FROM users WHERE receive_promotional_email = FALSE "
                    + "AND (lower(email) = :address OR lower(username) = :address)",
            nativeQuery = true)
    long countPromotionalOptOutsMatchingEmailAddress(@Param("address") String address);
}
