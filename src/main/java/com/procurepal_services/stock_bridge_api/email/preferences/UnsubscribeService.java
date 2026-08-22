package com.procurepal_services.stock_bridge_api.email.preferences;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Honours an unsubscribe: verify the token, then clear {@code
 * receive_promotional_email} on every user row that holds the address, in every
 * tenant.
 *
 * <h2>WHAT THIS DELIBERATELY DOES NOT DO: it does not stop transactional mail</h2>
 * The original request for this feature said an unsubscribed address "will receive
 * no emails". That is narrowed here, on purpose, and this paragraph is the record
 * of the decision so nobody later reads the code as an incomplete implementation of
 * the sentence.
 *
 * <p>Only marketing is switched off. Order receipts, fulfilment updates, payment
 * outcomes, password-change notices and verification links all keep flowing. A
 * customer cannot opt out of the receipt for goods they have paid for: that mail
 * is part of the transaction, not an approach to them, and in several
 * jurisdictions it is the record of a sale. Suppressing it would mean a buyer
 * spends money and is told nothing, a delivery arrives with no notice, and every
 * one of those becomes a support call that ends in a refund. It would also break
 * ProcurePal's own operations, which learn about new orders by email.
 *
 * <p>The distinction is not an invention of this class either - it is what {@link
 * com.procurepal_services.stock_bridge_api.email.EmailKind} exists to express, it
 * is the reason {@code users} carries two independent columns rather than one, and
 * it is what CAN-SPAM and the GDPR both actually require: an opt-out from
 * commercial messages, not from service messages. Somebody who wants no mail at
 * all closes their account.
 *
 * <h2>Why this writes across tenants with a native query</h2>
 * {@link com.procurepal_services.stock_bridge_api.entity.User} is a {@code
 * TenantAwareEntity}, so JPQL and derived queries carry Hibernate's tenant filter
 * whenever it happens to be enabled. On this request path it is not: the endpoint
 * is unauthenticated, so {@code TenantResolutionFilter} leaves {@code
 * TenantContext} empty and the filter disabled for the whole request. Relying on
 * "the filter is off here" would make the behaviour of this method depend on how
 * its caller was authenticated - fine today, silently wrong the first time
 * anything else calls it - so it is written as a native statement, which the
 * filter never rewrites, and therefore reads and writes across all tenants,
 * always, identically. That is the same reasoning, and the same precedent, as the
 * native count queries on {@code UserRepository}; read the block comment there.
 *
 * <p>{@code TenantScopeExecutor} is the wrong tool for the same reason it is wrong
 * there, and more so for a write: it re-points the filter at <em>one</em> named
 * client, and there is no client to name. The input is a bare address that may be
 * held by users in several tenants at once - one person with accounts at two
 * companies on the platform - and all of them must be silenced, because the human
 * reading that inbox objected once and does not have to object per tenant.
 *
 * <p>The statement lives here rather than on {@code UserRepository} because it is a
 * bulk write rather than a read: a {@code @Modifying} repository method would need
 * its own transaction annotation on a shared interface, whereas here the boundary
 * is visible next to the thing it protects. The usual caveat applies - a bulk
 * update bypasses the persistence context, so any {@code User} already loaded in
 * this session would keep the stale flag. Nothing is loaded on this path (the
 * whole request is one statement), and that is worth preserving: if this ever
 * grows an entity read, it must happen before the update or not at all.
 *
 * <p>{@code updated_at} is not set here and must not be: {@code
 * trg_users_set_updated_at} (V1) maintains it on every UPDATE, including this one.
 *
 * <h2>Idempotent by construction</h2>
 * Mail providers retry a one-click POST, sometimes several times, sometimes days
 * apart. The {@code WHERE receive_promotional_email = TRUE} guard means the second
 * and subsequent attempts match zero rows and change nothing - no wasted write, no
 * churned {@code updated_at}, and identical output. Idempotence here is not a
 * property of a lock or a dedupe table; it is a property of the statement being a
 * set-to-a-constant.
 *
 * <h2>Why the caller learns nothing about the address</h2>
 * The row count is logged and then dropped on the floor. Every caller gets the same
 * answer whether the address belongs to fifty users, one, or nobody at all - see
 * {@link UnsubscribeController}. An endpoint that answered differently for a known
 * address would be a membership oracle for ProcurePal's entire customer list,
 * queryable by anyone holding a single valid token... except that it would be far
 * worse than that, because the token is not needed to observe a difference in
 * timing or status if the code branches before verifying. Hence: verify first,
 * then act, then say the same thing regardless.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UnsubscribeService {

    /**
     * Matches on username as well as email for the reason {@code UserRepository}'s
     * lookups do: a tenant's first user signs up with an email address AS their
     * username and often has no separate {@code email} value, so matching {@code
     * email} alone would silently unsubscribe nobody for the account holder of
     * every company on the platform - the single most likely person to click the
     * button.
     */
    private static final String UNSUBSCRIBE_SQL =
            "UPDATE users SET receive_promotional_email = FALSE "
                    + "WHERE receive_promotional_email = TRUE "
                    + "AND (lower(email) = :address OR lower(username) = :address)";

    @PersistenceContext
    private EntityManager entityManager;

    private final UnsubscribeTokenService tokenService;

    /**
     * @param token the {@code token} query parameter from the {@code
     *     List-Unsubscribe} URL; anything that does not verify is refused
     * @throws InvalidUnsubscribeTokenException if the token is missing, malformed,
     *     forged, or signed with a key this deploy does not hold. This is the only
     *     way out other than success, and it says nothing about any address.
     */
    @Transactional
    public void unsubscribe(String token) {
        String address = tokenService.addressFrom(token);
        if (address == null) {
            throw new InvalidUnsubscribeTokenException();
        }

        int rows = entityManager
                .createNativeQuery(UNSUBSCRIBE_SQL)
                .setParameter("address", address)
                .executeUpdate();

        // Info, and worth it: "I unsubscribed and you kept emailing me" has to be
        // answerable, and this is the record that the request arrived and what it
        // changed. Zero is completely ordinary - a repeat POST, or an address that
        // is a client's contact of record rather than a user's - and is logged the
        // same way rather than as a warning, because there is nothing to act on.
        log.info("Honoured a one-click unsubscribe; {} user row(s) opted out of promotional email. "
                + "Transactional mail to this address is unaffected.", rows);
    }
}
