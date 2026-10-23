package com.procurepal_services.stock_bridge_api.branch;

import com.procurepal_services.stock_bridge_api.branch.dto.BranchHealthResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Branch Health (MULTI_BRANCH_BUILD_PLAN.md section 3.3): whether every stock figure is where the
 * branch model says it is. Cross-tenant on purpose - it is for the platform operator - so it is plain
 * SQL with the company named in every join, never the tenant filter, like {@code SellingHealthService}.
 */
@Service
@RequiredArgsConstructor
public class BranchHealthService {

    /** Companies whose branch rows are the truth: they have had more than one branch. */
    private static final String MULTI = """
            multi AS (SELECT client_id FROM branches GROUP BY client_id HAVING COUNT(*) > 1)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public BranchHealthResponse health() {
        long companies = count("SELECT COUNT(*) FROM clients");
        long multiBranch = count("WITH " + MULTI + " SELECT COUNT(*) FROM multi");
        long withoutDefault = count("""
                SELECT COUNT(*) FROM clients c
                WHERE NOT EXISTS (SELECT 1 FROM branches b WHERE b.client_id = c.id AND b.is_default)
                """);
        long foreignBranch = count("""
                SELECT COUNT(*) FROM stock_movements m
                JOIN branches b ON b.id = m.branch_id
                WHERE b.client_id <> m.client_id
                """);
        long totalMismatch = count("WITH " + MULTI + """
                SELECT COUNT(*) FROM products p
                LEFT JOIN (SELECT product_id, SUM(quantity_on_hand) AS total FROM branch_stock GROUP BY product_id) s
                       ON s.product_id = p.id
                WHERE p.client_id IN (SELECT client_id FROM multi)
                  AND p.quantity_on_hand <> COALESCE(s.total, 0)
                """);
        long mirrorDrift = count("WITH " + MULTI + """
                SELECT COUNT(*) FROM products p
                JOIN branches b ON b.client_id = p.client_id AND b.is_default
                LEFT JOIN branch_stock bs ON bs.branch_id = b.id AND bs.product_id = p.id
                WHERE p.client_id NOT IN (SELECT client_id FROM multi)
                  AND p.quantity_on_hand <> COALESCE(bs.quantity_on_hand, 0)
                """);
        // Each branch's figure against its own ledger. Every movement type is given its sign here on
        // purpose, as in StockMovementRepository.netChangeAfter: a type added later counts for nothing
        // until someone decides what it does to a shelf.
        long ledgerDrift = count("WITH " + MULTI + """
                , ledger AS (
                    SELECT m.client_id, m.product_id, m.branch_id,
                           SUM(CASE m.movement_type
                                   WHEN 'IN' THEN m.quantity
                                   WHEN 'OUT' THEN -m.quantity
                                   WHEN 'ADJUSTMENT' THEN m.quantity
                                   WHEN 'TRANSFER_IN' THEN m.quantity
                                   WHEN 'TRANSFER_OUT' THEN -m.quantity
                                   ELSE 0 END) AS net
                    FROM stock_movements m
                    GROUP BY m.client_id, m.product_id, m.branch_id
                ), figures AS (
                    SELECT p.client_id, p.id AS product_id, b.id AS branch_id, p.quantity_on_hand AS figure
                    FROM products p
                    JOIN branches b ON b.client_id = p.client_id
                    WHERE p.client_id NOT IN (SELECT client_id FROM multi)
                    UNION ALL
                    SELECT bs.client_id, bs.product_id, bs.branch_id, bs.quantity_on_hand
                    FROM branch_stock bs
                    WHERE bs.client_id IN (SELECT client_id FROM multi)
                )
                SELECT COUNT(*) FROM figures f
                FULL JOIN ledger l
                       ON l.client_id = f.client_id AND l.product_id = f.product_id AND l.branch_id = f.branch_id
                WHERE COALESCE(f.figure, 0) <> COALESCE(l.net, 0)
                """);
        return new BranchHealthResponse(
                companies,
                multiBranch,
                withoutDefault,
                foreignBranch,
                totalMismatch,
                mirrorDrift,
                ledgerDrift,
                withoutDefault == 0 && foreignBranch == 0 && totalMismatch == 0);
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, new MapSqlParameterSource(), Long.class);
        return value == null ? 0 : value;
    }
}
