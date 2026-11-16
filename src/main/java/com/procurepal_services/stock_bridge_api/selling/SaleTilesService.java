package com.procurepal_services.stock_bridge_api.selling;

import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The picture tiles on Record a sale (selling till Step 1 fixes): what to tap, so nobody has to know to
 * search. The company's most-sold products over the last 30 days first, then its other priced products
 * by name, then any by name - active ones only, up to {@code limit}.
 */
@Service
@RequiredArgsConstructor
public class SaleTilesService {

    static final int MAX = 24;

    private final NamedParameterJdbcTemplate jdbc;
    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public List<ProductResponse> tiles(UUID clientId, int limit) {
        int wanted = Math.max(1, Math.min(limit, MAX));
        List<UUID> mostSold = jdbc.queryForList(
                """
                SELECT o.product_id FROM stock_movements o
                JOIN products p ON p.id = o.product_id AND p.is_active
                WHERE o.client_id = :clientId AND o.movement_type = 'OUT' AND o.sale_id IS NOT NULL
                  AND o.occurred_at >= :since
                GROUP BY o.product_id
                ORDER BY COUNT(*) DESC, MAX(o.occurred_at) DESC
                LIMIT :limit
                """,
                new MapSqlParameterSource()
                        .addValue("clientId", clientId)
                        .addValue("since", OffsetDateTime.now().minusDays(30))
                        .addValue("limit", wanted),
                UUID.class);

        Set<UUID> order = new LinkedHashSet<>(mostSold);
        List<Product> active = order.size() < wanted
                ? productRepository.findAllByClientIdAndActiveTrueOrderByNameAsc(clientId)
                : List.of();
        active.stream().filter(p -> p.getUnitPrice() != null).forEach(p -> addUpTo(order, p.getId(), wanted));
        active.forEach(p -> addUpTo(order, p.getId(), wanted));

        Map<UUID, Product> byId = (active.isEmpty() ? productRepository.findAllById(order) : active).stream()
                .filter(p -> clientId.equals(p.getClientId()))
                .collect(Collectors.toMap(Product::getId, Function.identity(), (a, b) -> a));
        List<ProductResponse> tiles = new ArrayList<>();
        for (UUID id : order) {
            Product product = byId.get(id);
            if (product == null) {
                product = productRepository.findById(id).filter(p -> clientId.equals(p.getClientId())).orElse(null);
            }
            if (product != null) {
                tiles.add(ProductResponse.from(product));
            }
        }
        return tiles;
    }

    private static void addUpTo(Set<UUID> order, UUID id, int wanted) {
        if (order.size() < wanted) {
            order.add(id);
        }
    }
}
