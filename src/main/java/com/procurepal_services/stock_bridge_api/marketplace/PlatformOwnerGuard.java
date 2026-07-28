package com.procurepal_services.stock_bridge_api.marketplace;

import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantAwareEntity;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The second, independent gate on every {@code /api/marketplace/admin/**} surface.
 *
 * <h2>Why a second gate exists at all</h2>
 * MANAGE_MARKETPLACE, MANAGE_MARKETPLACE_ORDERS and VIEW_MARKETPLACE_ANALYTICS are
 * held by EVERY tenant's OWNER. They have to be: permissions are global rows
 * attached to global roles, so "grant this to ProcurePal's owner only" is not
 * expressible without per-tenant roles. {@code @PreAuthorize} therefore proves the
 * caller has the right job, and this guard proves they work for the right company.
 * Both are required; neither is sufficient.
 *
 * <h2>How to use it</h2>
 * <pre>{@code
 * @PostMapping("/api/marketplace/admin/orders/{id}/status")
 * @PreAuthorize("hasAuthority('MANAGE_MARKETPLACE_ORDERS')")
 * public OrderResponse advance(@PathVariable UUID id, @Valid @RequestBody StatusRequest body) {
 *     platformOwnerGuard.requirePlatformOwner();   // throws -> 403 ApiError
 *     ...
 * }
 * }</pre>
 * There is nothing else to wire: {@link MarketplaceAccessExceptionHandler} turns the
 * exception into a 403 {@code ApiError} for every controller in the application, so
 * a feature package does not need its own advice for it.
 *
 * <h2>Why it reads the database instead of a token claim</h2>
 * The access token also carries a {@code platformOwner} claim, but that is for the
 * frontend to decide what to render. Authorization reads the clients row, so
 * flipping the flag takes effect on the next request rather than whenever the
 * holder's 15-minute token happens to expire.
 */
@Component
@RequiredArgsConstructor
public class PlatformOwnerGuard {

    private final ClientRepository clientRepository;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Asserts the current request's tenant is the platform owner and returns it.
     *
     * @throws PlatformOwnerNotAllowedException if there is no tenant context, the
     *     client no longer exists, or it is an ordinary tenant. All three collapse
     *     into one 403 on purpose - a caller probing this endpoint learns only that
     *     they may not use it.
     */
    @Transactional(readOnly = true)
    public Client requirePlatformOwner() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new PlatformOwnerNotAllowedException();
        }
        return clientRepository.findById(tenantId)
                .filter(Client::isPlatformOwner)
                .orElseThrow(PlatformOwnerNotAllowedException::new);
    }

    /** Non-throwing variant, for deciding what to include in a response rather than whether to answer. */
    @Transactional(readOnly = true)
    public boolean isCurrentTenantPlatformOwner() {
        UUID tenantId = TenantContext.get();
        return tenantId != null
                && clientRepository.findById(tenantId).map(Client::isPlatformOwner).orElse(false);
    }

    /**
     * The platform owner, if one has been seeded. Public catalog endpoints use this
     * to filter explicitly on the seller's client_id, because they run with no
     * TenantContext and therefore with the Hibernate tenant filter switched off -
     * see the PERMIT_ALL_PATHS comment in SecurityConfig.
     *
     * Returns empty rather than throwing so an unseeded marketplace renders as an
     * empty catalog, not a 500.
     */
    @Transactional(readOnly = true)
    public Optional<Client> findPlatformOwner() {
        return clientRepository.findByPlatformOwnerTrue();
    }

    /**
     * Runs {@code work} with the Hibernate tenant filter lifted, after proving the
     * caller is the platform owner.
     *
     * This exists for exactly one legitimate need: ProcurePal's fulfilment queue,
     * customers view and marketplace analytics all read {@code orders}, which is a
     * tenant-scoped entity whose client_id is the BUYER. Under ProcurePal's own
     * tenant filter those queries match nothing. Rather than have several modules
     * each reach for {@code Session.disableFilter(...)} - at which point one of them
     * eventually forgets to re-enable it, or forgets the ownership check - the
     * escape hatch lives here, is guarded, and restores the previous filter state in
     * a finally block.
     *
     * Explicit client_id predicates (TenantScopedRepository, and the buyer-facing
     * order finders) are unaffected and still hold, which is why lifting layer 1
     * here is safe: the code inside is auditable and reads across tenants on purpose.
     *
     * @throws PlatformOwnerNotAllowedException before {@code work} is ever invoked, if
     *     the caller is not the platform owner.
     */
    public <T> T readAcrossTenants(Supplier<T> work) {
        requirePlatformOwner();

        Session session = entityManager.unwrap(Session.class);
        UUID tenantId = TenantContext.get();
        boolean filterWasEnabled = session.getEnabledFilter(TenantAwareEntity.TENANT_FILTER_NAME) != null;
        if (filterWasEnabled) {
            session.disableFilter(TenantAwareEntity.TENANT_FILTER_NAME);
        }
        try {
            return work.get();
        } finally {
            // Restore rather than leave it off: the rest of the request (response
            // serialization included, since open-in-view keeps this Session alive)
            // must go back to being tenant-filtered.
            if (filterWasEnabled) {
                session.enableFilter(TenantAwareEntity.TENANT_FILTER_NAME)
                        .setParameter(TenantAwareEntity.TENANT_FILTER_PARAM, tenantId);
            }
        }
    }
}
