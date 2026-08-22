package com.procurepal_services.stock_bridge_api.vendor;

import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.ClientType;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantAwareEntity;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The second, independent gate on every vendor surface - the exact counterpart of
 * {@code PlatformOwnerGuard}, which should be read first because it explains the
 * pattern this follows.
 *
 * <h2>Why a second gate exists at all</h2>
 * Permissions are global rows attached to global roles, so "grant this to vendors
 * only" is not expressible: MANAGE_MARKETPLACE and MANAGE_MARKETPLACE_ORDERS are
 * held by the VENDOR role AND by every tenant's OWNER, because ProcurePal's own
 * staff need them too. {@code @PreAuthorize} therefore proves the caller has the
 * right job, and this guard proves their COMPANY is the right kind. Both are
 * required; neither is sufficient.
 *
 * <p>The one code that IS vendor-specific is VIEW_OWN_SALES_ANALYTICS, which V11
 * introduced precisely so a vendor never has to be handed
 * VIEW_MARKETPLACE_ANALYTICS - a code that every tenant OWNER already holds and
 * whose plain meaning at the time was "the entire marketplace, every seller's
 * revenue". M6 narrowed what that code actually reaches: the routes behind it now
 * report ProcurePal's OWN sales, and cross-seller revenue moved to the super admin
 * principal. The permission was not renamed, so the code and its plain meaning have
 * drifted apart - read it as "the operator's own sales view, in more depth than a
 * vendor's" and check {@code MarketplaceAnalyticsService} rather than the name.
 * Note the consequence: ProcurePal's staff do NOT hold the new code, so an own-sales
 * endpoint must accept either
 * ({@code hasAnyAuthority('VIEW_OWN_SALES_ANALYTICS','VIEW_MARKETPLACE_ANALYTICS')})
 * and lean on {@link #requireSeller()} for the second gate. That endpoint now
 * exists - see {@code vendor.analytics.VendorSalesAnalyticsController}, which is
 * the worked example of this whole arrangement.
 *
 * <h2>How to use it</h2>
 * <pre>{@code
 * @GetMapping("/api/vendor/orders")
 * @PreAuthorize("hasAuthority('MANAGE_MARKETPLACE_ORDERS')")
 * public Page<OrderResponse> myOrders(Pageable pageable) {
 *     Client vendor = vendorGuard.requireVendor();   // throws -> 403 ApiError
 *     ...
 * }
 * }</pre>
 * There is nothing else to wire: {@link VendorAccessExceptionHandler} turns the
 * exception into a 403 {@code ApiError} for every controller in the application,
 * so a feature package does not need its own advice for it.
 *
 * <h2>Which method to call</h2>
 * The distinction matters and getting it wrong locks ProcurePal out of its own
 * marketplace.
 * <ul>
 *   <li>{@link #requireVendor()} - for surfaces that exist ONLY for vendor
 *       accounts. A vendor-only onboarding screen, say. ProcurePal is refused
 *       here, correctly: it is a {@link ClientType#COMPANY} that happens to own
 *       the platform.</li>
 *   <li>{@link #requireSeller()} - for surfaces about SELLING: my catalogue, my
 *       order queue, my sales analytics, my pickup addresses. Vendors and
 *       ProcurePal both belong here, and this is the method most sell-side code
 *       wants. Using requireVendor() for these is the single most likely mistake
 *       in this feature.</li>
 * </ul>
 *
 * <h2>Why it reads the database instead of a token claim</h2>
 * Same reason PlatformOwnerGuard does. The access token DOES carry the client type
 * - {@code JwtClaims.CLIENT_TYPE}, mirrored onto the login response and /api/me -
 * but that is for the frontend to decide what to RENDER. Authorization reads the
 * clients row, so changing an account's kind takes effect on the next request
 * rather than whenever the holder's 15-minute token happens to expire. Nothing in
 * this class ever consults the claim, and nothing on the server should: honouring
 * it would mean honouring a decision the platform had already reversed.
 */
@Component
@RequiredArgsConstructor
public class VendorGuard {

    private final ClientRepository clientRepository;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Asserts the current request's tenant is a vendor account, and returns it.
     *
     * <p>Note this REFUSES the platform owner. Use {@link #requireSeller()} for
     * anything about selling, which ProcurePal also does.
     *
     * @throws VendorNotAllowedException if there is no tenant context, the client
     *     no longer exists, or it is not a vendor. All three collapse into one 403
     *     on purpose - a caller probing this endpoint learns only that they may
     *     not use it.
     */
    @Transactional(readOnly = true)
    public Client requireVendor() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw VendorNotAllowedException.notAVendor();
        }
        return clientRepository.findById(tenantId)
                .filter(Client::isVendor)
                .orElseThrow(VendorNotAllowedException::notAVendor);
    }

    /**
     * Asserts the current request's tenant may SELL on the marketplace - a vendor,
     * or the platform owner - and returns it.
     *
     * <p>This is the check that belongs on catalogue, order-queue, sales-analytics
     * and pickup-address surfaces. Callers still have to scope their queries to
     * the returned client's id: this answers "may you sell", never "which rows are
     * yours".
     *
     * @throws VendorNotAllowedException if there is no tenant context, the client
     *     no longer exists, or it is an ordinary buying company.
     */
    @Transactional(readOnly = true)
    public Client requireSeller() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw VendorNotAllowedException.notASeller();
        }
        return clientRepository.findById(tenantId)
                .filter(Client::canSell)
                .orElseThrow(VendorNotAllowedException::notASeller);
    }

    /** Non-throwing variant, for deciding what to include in a response rather than whether to answer. */
    @Transactional(readOnly = true)
    public boolean isCurrentTenantVendor() {
        UUID tenantId = TenantContext.get();
        return tenantId != null
                && clientRepository.findById(tenantId).map(Client::isVendor).orElse(false);
    }

    /**
     * Non-throwing "may this tenant sell". The counterpart of
     * {@link #requireSeller()}, for the same render-vs-refuse distinction as
     * {@link #isCurrentTenantVendor()}.
     */
    @Transactional(readOnly = true)
    public boolean canCurrentTenantSell() {
        UUID tenantId = TenantContext.get();
        return tenantId != null
                && clientRepository.findById(tenantId).map(Client::canSell).orElse(false);
    }

    /**
     * Runs {@code work} with the Hibernate tenant filter lifted, after proving the
     * caller may sell.
     *
     * <p>This exists for exactly one need, and it is the same one
     * {@code PlatformOwnerGuard.readAcrossTenants} exists for, arrived at from the
     * other side: {@code orders} is tenant-scoped to the BUYER, so a seller's own
     * order queue, customers view and sales analytics match nothing under the
     * seller's own tenant filter. Rather than have the vendor modules each reach
     * for {@code Session.disableFilter(...)} - at which point one of them
     * eventually forgets to re-enable it, or forgets the ownership check - the
     * escape hatch lives here, is guarded, and restores the previous filter state
     * in a finally block.
     *
     * <h2>What this does NOT do, and the caller's obligation</h2>
     * It lifts layer 1 only. Explicit predicates (TenantScopedRepository, and
     * OrderRepository's seller-side finders) are unaffected and still hold, which
     * is what makes lifting the filter safe here. But nothing in this method
     * restricts the rows {@code work} reads: it is the CALLER's job to pass the
     * returned seller's id into a {@code seller_client_id} predicate. Wrapping an
     * unscoped query in this would show one vendor every other vendor's orders,
     * which is the worst failure this feature can produce. Use
     * {@code findAllBySellerClientId...} and friends, never {@code findAll()}.
     *
     * @throws VendorNotAllowedException before {@code work} is ever invoked, if the
     *     caller may not sell.
     */
    public <T> T readOwnSales(Supplier<T> work) {
        requireSeller();

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
