package com.procurepal_services.stock_bridge_api.superadmin;

import com.procurepal_services.stock_bridge_api.analytics.AnalyticsService;
import com.procurepal_services.stock_bridge_api.analytics.dto.AnalyticsSummaryResponse;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import com.procurepal_services.stock_bridge_api.superadmin.dto.PlatformAggregateResponse;
import com.procurepal_services.stock_bridge_api.superadmin.dto.TenantBreakdownEntry;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Platform-wide numbers for GET /api/superadmin/analytics/aggregate. Built by
 * calling AnalyticsService.summaryForClient once per client and folding the
 * results, rather than a separate cross-tenant SQL aggregation - this reuses
 * the exact same IN/OUT value and active-product-count logic the tenant-facing
 * and per-client super admin analytics endpoints already use, so there's only
 * ever one place those sums are computed.
 */
@Service
@RequiredArgsConstructor
public class SuperAdminAggregateService {

    private final ClientRepository clientRepository;
    private final UserRepository userRepository;
    private final AnalyticsService analyticsService;

    @Transactional(readOnly = true)
    public PlatformAggregateResponse aggregate(OffsetDateTime from, OffsetDateTime to) {
        List<Client> clients = clientRepository.findAll();

        long totalActiveClients = 0;
        long totalActiveUsers = 0;
        long totalActiveProducts = 0;
        BigDecimal totalInValue = BigDecimal.ZERO;
        BigDecimal totalOutValue = BigDecimal.ZERO;
        List<TenantBreakdownEntry> breakdown = new ArrayList<>(clients.size());

        for (Client client : clients) {
            AnalyticsSummaryResponse summary = analyticsService.summaryForClient(client.getId(), from, to);
            long activeUserCount = userRepository.countByClientIdAndActiveTrue(client.getId());

            if (client.isActive()) {
                totalActiveClients++;
            }
            totalActiveUsers += activeUserCount;
            totalActiveProducts += summary.activeProductCount();
            totalInValue = totalInValue.add(summary.totalInValue());
            totalOutValue = totalOutValue.add(summary.totalOutValue());

            breakdown.add(new TenantBreakdownEntry(
                    client.getId(),
                    client.getName(),
                    client.isActive(),
                    activeUserCount,
                    summary.activeProductCount(),
                    summary.totalInValue(),
                    summary.totalOutValue()));
        }

        return new PlatformAggregateResponse(
                totalActiveClients, totalActiveUsers, totalActiveProducts, totalInValue, totalOutValue, breakdown);
    }
}
