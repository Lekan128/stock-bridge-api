package com.procurepal_services.stock_bridge_api.analytics;

import com.procurepal_services.stock_bridge_api.analytics.dto.AnalyticsSummaryResponse;
import com.procurepal_services.stock_bridge_api.analytics.dto.LowStockSummaryResponse;
import com.procurepal_services.stock_bridge_api.analytics.dto.MovementsOverTimePoint;
import com.procurepal_services.stock_bridge_api.analytics.dto.TopProductEntry;
import com.procurepal_services.stock_bridge_api.entity.MovementType;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.repository.StockMovementRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deliberately business-model-agnostic: IN/OUT are just ledger directions
 * (see StockMovement), so "spend" (IN value) and "revenue" (OUT value) are
 * two names for the same underlying sums depending on whether a tenant is
 * tracking purchases or sales - nothing here assumes one over the other.
 */
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private static final Set<String> VALID_GRANULARITIES = Set.of("day", "week", "month");

    private final StockMovementRepository stockMovementRepository;
    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public AnalyticsSummaryResponse summary(OffsetDateTime from, OffsetDateTime to) {
        return summaryForClient(requireTenantId(), from, to);
    }

    /**
     * Core aggregation for the summary endpoint, parameterized on client_id
     * rather than reading TenantContext directly - lets the tenant-facing
     * controller pass TenantContext's value and the super admin controller
     * pass a path variable, without duplicating any of these queries. Not
     * tenant-scoped by the Hibernate filter (super admin requests run with no
     * TenantContext set), so isolation here comes entirely from clientId being
     * passed explicitly into every query below.
     */
    @Transactional(readOnly = true)
    public AnalyticsSummaryResponse summaryForClient(UUID clientId, OffsetDateTime from, OffsetDateTime to) {
        OffsetDateTime[] range = resolveRange(from, to);

        BigDecimal totalInValue = stockMovementRepository.sumValue(clientId, MovementType.IN, range[0], range[1]);
        BigDecimal totalOutValue = stockMovementRepository.sumValue(clientId, MovementType.OUT, range[0], range[1]);
        long totalUnitsIn = stockMovementRepository.sumQuantity(clientId, MovementType.IN, range[0], range[1]);
        long totalUnitsOut = stockMovementRepository.sumQuantity(clientId, MovementType.OUT, range[0], range[1]);
        long lowStockProductCount = productRepository.countLowStockByClientId(clientId);
        long activeProductCount = productRepository.countByClientIdAndActive(clientId, true);

        return new AnalyticsSummaryResponse(
                totalInValue, totalOutValue, totalUnitsIn, totalUnitsOut, lowStockProductCount, activeProductCount);
    }

    @Transactional(readOnly = true)
    public List<MovementsOverTimePoint> movementsOverTime(OffsetDateTime from, OffsetDateTime to, String granularityParam) {
        return movementsOverTimeForClient(requireTenantId(), from, to, granularityParam);
    }

    @Transactional(readOnly = true)
    public List<MovementsOverTimePoint> movementsOverTimeForClient(
            UUID clientId, OffsetDateTime from, OffsetDateTime to, String granularityParam) {
        String granularity = parseGranularity(granularityParam);
        OffsetDateTime[] range = resolveRange(from, to);

        return stockMovementRepository.movementsOverTime(clientId, granularity, range[0], range[1]).stream()
                .map(this::toPoint)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TopProductEntry> topProducts(
            OffsetDateTime from, OffsetDateTime to, String byParam, String directionParam, int limit) {
        return topProductsForClient(requireTenantId(), from, to, byParam, directionParam, limit);
    }

    @Transactional(readOnly = true)
    public List<TopProductEntry> topProductsForClient(
            UUID clientId, OffsetDateTime from, OffsetDateTime to, String byParam, String directionParam, int limit) {
        boolean rankByValue = parseBy(byParam);
        MovementType direction = parseDirection(directionParam);
        OffsetDateTime[] range = resolveRange(from, to);
        int effectiveLimit = Math.max(1, Math.min(limit, 100));

        List<Object[]> rows = rankByValue
                ? stockMovementRepository.topProductsByValue(clientId, direction.name(), range[0], range[1], effectiveLimit)
                : stockMovementRepository.topProductsByQuantity(clientId, direction.name(), range[0], range[1], effectiveLimit);
        return rows.stream().map(this::toTopProductEntry).toList();
    }

    @Transactional(readOnly = true)
    public LowStockSummaryResponse lowStockSummary() {
        return lowStockSummaryForClient(requireTenantId());
    }

    @Transactional(readOnly = true)
    public LowStockSummaryResponse lowStockSummaryForClient(UUID clientId) {
        List<ProductResponse> products = productRepository.findLowStockByClientId(clientId).stream()
                .map(ProductResponse::from)
                .toList();
        return new LowStockSummaryResponse(products.size(), products);
    }

    /** Defaults to "current month to date" when either bound is omitted, per the endpoint contract. */
    private OffsetDateTime[] resolveRange(OffsetDateTime from, OffsetDateTime to) {
        OffsetDateTime effectiveTo = to != null ? to : OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime effectiveFrom = from != null
                ? from
                : effectiveTo.toLocalDate().withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        return new OffsetDateTime[] {effectiveFrom, effectiveTo};
    }

    private String parseGranularity(String raw) {
        String normalized = raw == null ? "day" : raw.trim().toLowerCase();
        if (!VALID_GRANULARITIES.contains(normalized)) {
            throw new InvalidAnalyticsParameterException("granularity", raw, "day, week, month");
        }
        return normalized;
    }

    private boolean parseBy(String raw) {
        String normalized = raw == null ? "quantity" : raw.trim().toLowerCase();
        return switch (normalized) {
            case "value" -> true;
            case "quantity" -> false;
            default -> throw new InvalidAnalyticsParameterException("by", raw, "quantity, value");
        };
    }

    private MovementType parseDirection(String raw) {
        String normalized = raw == null ? "in" : raw.trim().toLowerCase();
        return switch (normalized) {
            case "in" -> MovementType.IN;
            case "out" -> MovementType.OUT;
            default -> throw new InvalidAnalyticsParameterException("direction", raw, "in, out");
        };
    }

    private MovementsOverTimePoint toPoint(Object[] row) {
        return new MovementsOverTimePoint(
                (String) row[0],
                (BigDecimal) row[1],
                (BigDecimal) row[2],
                ((Number) row[3]).longValue(),
                ((Number) row[4]).longValue());
    }

    private TopProductEntry toTopProductEntry(Object[] row) {
        return new TopProductEntry(
                (UUID) row[0], (String) row[1], (String) row[2], (BigDecimal) row[3], ((Number) row[4]).longValue());
    }

    private UUID requireTenantId() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context set");
        }
        return tenantId;
    }
}
