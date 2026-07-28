package com.procurepal_services.stock_bridge_api.tenant;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.Session;
import org.springframework.stereotype.Component;

/**
 * Runs a unit of work AS a named tenant, moving both layers of isolation at once.
 *
 * <h2>Why this is needed at all</h2>
 * Two flows in the marketplace legitimately write rows belonging to a client that
 * is not the request's client:
 * <ul>
 *   <li>Applying a verified Monnify payment. That runs on an unauthenticated
 *       webhook thread, so TenantContext is empty and the Hibernate filter was
 *       never enabled - yet it has to create/update the BUYER's product rows, and
 *       {@link TenantAwareEntity}'s {@code @PrePersist} refuses to persist without
 *       a tenant.</li>
 *   <li>ProcurePal cancelling an order. That runs under ProcurePal's own tenant
 *       filter, and reversing the buyer's incoming stock means reading and writing
 *       the buyer's products, which that filter hides.</li>
 * </ul>
 *
 * <h2>Why it moves the Hibernate filter too, rather than only TenantContext</h2>
 * Setting TenantContext alone is the trap: {@code @PrePersist} would then stamp the
 * right client_id, but every JPQL query in the same block would still carry the
 * OUTER tenant's filter predicate and silently return nothing. The two have to move
 * together, and the previous state has to be restored exactly - including
 * "disabled", which is the correct end state on a webhook thread.
 *
 * <h2>Why this is not a hole in tenant isolation</h2>
 * It re-points the filter at a specific client rather than lifting it (unlike
 * {@code PlatformOwnerGuard.readAcrossTenants}, which lifts it for a proven platform
 * owner). Work inside still sees exactly one tenant's rows; it is just a different
 * one, chosen by server-side code from {@code order.client_id} - never from a request
 * parameter. Callers must derive the id from a row they already loaded, never from
 * user input.
 */
@Component
public class TenantScopeExecutor {

    @PersistenceContext
    private EntityManager entityManager;

    public <T> T callAs(UUID clientId, Supplier<T> work) {
        if (clientId == null) {
            throw new IllegalArgumentException("Cannot run work as a null tenant");
        }
        UUID previousTenantId = TenantContext.get();
        Session session = entityManager.unwrap(Session.class);
        boolean filterWasEnabled = session.getEnabledFilter(TenantAwareEntity.TENANT_FILTER_NAME) != null;

        TenantContext.set(clientId);
        session.enableFilter(TenantAwareEntity.TENANT_FILTER_NAME)
                .setParameter(TenantAwareEntity.TENANT_FILTER_PARAM, clientId);
        try {
            return work.get();
        } finally {
            // Restore precisely. Leaving the buyer's filter enabled would leak into
            // the rest of a ProcurePal request (open-in-view keeps this Session alive
            // through response serialization); leaving it enabled at all on a webhook
            // thread would outlive the request on a pooled thread.
            if (filterWasEnabled && previousTenantId != null) {
                session.enableFilter(TenantAwareEntity.TENANT_FILTER_NAME)
                        .setParameter(TenantAwareEntity.TENANT_FILTER_PARAM, previousTenantId);
            } else {
                session.disableFilter(TenantAwareEntity.TENANT_FILTER_NAME);
            }
            if (previousTenantId == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenantId);
            }
        }
    }

    public void runAs(UUID clientId, Runnable work) {
        callAs(clientId, () -> {
            work.run();
            return null;
        });
    }
}
