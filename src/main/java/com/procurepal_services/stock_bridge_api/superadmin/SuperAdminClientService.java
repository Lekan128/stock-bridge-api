package com.procurepal_services.stock_bridge_api.superadmin;

import com.procurepal_services.stock_bridge_api.analytics.AnalyticsService;
import com.procurepal_services.stock_bridge_api.analytics.dto.AnalyticsSummaryResponse;
import com.procurepal_services.stock_bridge_api.client.ClientIdentifierTakenException;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import com.procurepal_services.stock_bridge_api.superadmin.dto.SuperAdminClientDetail;
import com.procurepal_services.stock_bridge_api.superadmin.dto.SuperAdminClientSummary;
import com.procurepal_services.stock_bridge_api.superadmin.dto.UpdateClientRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs the tenant-management side of /api/superadmin - list/detail/status
 * toggle over clients. Unlike tenant-scoped services, there's no
 * TenantContext here (a super admin belongs to no tenant - see
 * SuperAdminPrincipal), so every query below takes a client id explicitly,
 * either from the path variable or from the Client row just loaded.
 */
@Service
@RequiredArgsConstructor
public class SuperAdminClientService {

    private final ClientRepository clientRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final AnalyticsService analyticsService;

    @Transactional(readOnly = true)
    public Page<SuperAdminClientSummary> list(String search, Boolean active, Pageable pageable) {
        return clientRepository.findAll(ClientSpecifications.search(search, active), pageable).map(this::toSummary);
    }

    @Transactional(readOnly = true)
    public SuperAdminClientDetail get(UUID clientId) {
        return toDetail(findOrThrow(clientId));
    }

    @Transactional
    public SuperAdminClientDetail updateStatus(UUID clientId, boolean active) {
        Client client = findOrThrow(clientId);
        client.setActive(active);
        return toDetail(client);
    }

    /**
     * Edits a tenant's own {@code clients} row on ProcurePal's behalf - the
     * super-admin counterpart to CompanyService.update, and the endpoint the user
     * asked for when they asked to manage "clients table information for
     * procurepal". It is general rather than platform-owner-only: see
     * SuperAdminClientController for why.
     *
     * <h2>Replace semantics, with one exception</h2>
     * name, adminEmail, phone and paymentTerms are assigned unconditionally, so a
     * phone the ops user cleared is written as NULL rather than silently kept -
     * same rule CompanyService.update follows. slug is the exception: null means
     * "not renaming" rather than "clear", because the column is NOT NULL and
     * unique so there is no coherent empty value for it. UpdateClientRequest has
     * the full reasoning, including what a rename breaks.
     *
     * <h2>Why the slug uniqueness check is here and also in the database</h2>
     * The pre-check below produces the same clean 409 a duplicate signup gets
     * (ClientIdentifierTakenException, reused rather than re-invented - a caller
     * should not have to learn two vocabularies for one collision). It is not the
     * guarantee: {@code uq_clients_slug} is, and two ops users renaming two tenants
     * to the same identifier in the same instant will have one of them hit it. That
     * lands as a DataIntegrityViolationException, which SuperAdminExceptionHandler
     * turns into a 409 as well - the check is for the message, the index is for the
     * correctness. The check is skipped when the slug is unchanged, or a plain
     * re-save of an unmodified form would report the row colliding with itself.
     *
     * <p>saveAndFlush rather than relying on the dirty-check at commit, for the
     * reason CompanyService gives: the response is then built from a row the
     * database has actually accepted, so a constraint violation surfaces inside the
     * request rather than during the commit that happens after the response has
     * been serialised.
     */
    @Transactional
    public SuperAdminClientDetail update(UUID clientId, UpdateClientRequest request) {
        Client client = findOrThrow(clientId);

        client.setName(request.name().trim());
        client.setAdminContactEmail(request.adminEmail().trim());
        client.setPhone(normalize(request.phone()));
        client.setPaymentTerms(request.paymentTerms());
        applySlugRename(client, request.slug());

        return toDetail(clientRepository.saveAndFlush(client));
    }

    /**
     * Note what this method cannot do, which is most of the point of it: there is
     * no path from a request body to {@code setPlatformOwner} or {@code setActive}
     * anywhere in this class. Suspension has its own endpoint above; the
     * platform-owner flag has no endpoint at all, by design (UpdateClientRequest
     * explains what moving it would do to the public catalog).
     */
    private void applySlugRename(Client client, String requestedSlug) {
        String slug = normalize(requestedSlug);
        if (slug == null || slug.equals(client.getSlug())) {
            return;
        }
        if (clientRepository.findBySlug(slug).isPresent()) {
            throw new ClientIdentifierTakenException(slug);
        }
        client.setSlug(slug);
    }

    /** Blank is how a form says "empty"; the database should say NULL. */
    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private SuperAdminClientSummary toSummary(Client client) {
        long userCount = userRepository.countByClientId(client.getId());
        long productCount = productRepository.countByClientId(client.getId());
        return new SuperAdminClientSummary(
                client.getId(),
                client.getName(),
                client.getSlug(),
                client.isActive(),
                client.getAdminContactEmail(),
                userCount,
                productCount,
                client.getCreatedAt());
    }

    /**
     * activeProductCount/lowStockProductCount come from
     * AnalyticsService.summaryForClient (from=to=null, so it resolves to
     * "current month to date") rather than a separate query here - both
     * figures are point-in-time counts unaffected by that range, and reusing
     * the same aggregation the tenant-facing/analytics-for-client endpoints
     * use keeps this in one place.
     */
    private SuperAdminClientDetail toDetail(Client client) {
        long userCount = userRepository.countByClientId(client.getId());
        long activeUserCount = userRepository.countByClientIdAndActiveTrue(client.getId());
        long productCount = productRepository.countByClientId(client.getId());
        AnalyticsSummaryResponse quickGlance = analyticsService.summaryForClient(client.getId(), null, null);

        return new SuperAdminClientDetail(
                client.getId(),
                client.getName(),
                client.getSlug(),
                client.isActive(),
                client.getAdminContactEmail(),
                userCount,
                productCount,
                client.getCreatedAt(),
                client.getUpdatedAt(),
                activeUserCount,
                quickGlance.activeProductCount(),
                quickGlance.lowStockProductCount(),
                client.getPhone(),
                client.isPlatformOwner(),
                client.getPaymentTerms());
    }

    private Client findOrThrow(UUID id) {
        return clientRepository.findById(id).orElseThrow(ClientNotFoundException::new);
    }
}
