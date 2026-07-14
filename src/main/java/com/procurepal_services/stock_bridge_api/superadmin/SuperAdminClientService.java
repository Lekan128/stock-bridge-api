package com.procurepal_services.stock_bridge_api.superadmin;

import com.procurepal_services.stock_bridge_api.analytics.AnalyticsService;
import com.procurepal_services.stock_bridge_api.analytics.dto.AnalyticsSummaryResponse;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import com.procurepal_services.stock_bridge_api.superadmin.dto.SuperAdminClientDetail;
import com.procurepal_services.stock_bridge_api.superadmin.dto.SuperAdminClientSummary;
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
                quickGlance.lowStockProductCount());
    }

    private Client findOrThrow(UUID id) {
        return clientRepository.findById(id).orElseThrow(ClientNotFoundException::new);
    }
}
