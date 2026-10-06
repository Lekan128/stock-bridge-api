package com.procurepal_services.stock_bridge_api.onboarding;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The first-week numbers for one shop, in one query. Plain SQL with the client id explicit: the
 * super admin's first-week list runs it with no tenant context, and the shop's own checklist with
 * its own id, so neither depends on the Hibernate tenant filter.
 */
@Component
@RequiredArgsConstructor
public class OnboardingQueries {

    /** The shop's own stock changes: not import opening stock, not Procurepaddy support's. */
    static final String OWN_MOVEMENTS = "FROM stock_movements m"
            + " LEFT JOIN users mu ON mu.id = m.created_by"
            + " LEFT JOIN roles mr ON mr.id = mu.role_id"
            + " WHERE m.client_id = c.id AND m.import_batch_id IS NULL"
            + " AND (mr.name IS NULL OR mr.name <> '" + SupportAccessService.ROLE + "')";

    static final String ACTIVITY_COLUMNS =
            "(SELECT count(*) FROM products p WHERE p.client_id = c.id AND p.is_active) AS products,"
            + " (SELECT min(p.created_at) FROM products p WHERE p.client_id = c.id) AS first_product_at,"
            + " (SELECT count(*) " + OWN_MOVEMENTS + ") AS stock_changes,"
            + " (SELECT min(COALESCE(m.occurred_at, m.created_at)) " + OWN_MOVEMENTS + ") AS first_stock_change_at,"
            + " (SELECT count(*) " + OWN_MOVEMENTS + " AND m.is_count) AS counts,"
            + " (SELECT count(*) " + OWN_MOVEMENTS + " AND COALESCE(m.occurred_at, m.created_at) > now() - interval '7 days') AS changes_last_7_days,"
            + " (SELECT count(DISTINCT (COALESCE(m.occurred_at, m.created_at) AT TIME ZONE 'Africa/Lagos')::date) " + OWN_MOVEMENTS
            + "   AND COALESCE(m.occurred_at, m.created_at) < c.created_at + interval '7 days') AS active_days_first_week,"
            + " (SELECT count(*) FROM products p WHERE p.client_id = c.id AND p.is_active"
            + "   AND p.low_stock_threshold IS NOT NULL AND p.quantity_on_hand <= p.low_stock_threshold) AS low_stock,"
            + " (SELECT count(*) FROM users u JOIN roles r ON r.id = u.role_id WHERE u.client_id = c.id"
            + "   AND u.is_active AND NOT u.is_root AND r.name <> '" + SupportAccessService.ROLE + "') AS staff,"
            + " (SELECT count(*) FROM product_list_files f WHERE f.client_id = c.id) AS list_files";

    private final NamedParameterJdbcTemplate jdbc;

    public ShopActivity activity(UUID clientId) {
        return jdbc.queryForObject(
                "SELECT " + ACTIVITY_COLUMNS + " FROM clients c WHERE c.id = :clientId",
                Map.of("clientId", clientId),
                (rs, rowNum) -> activity(rs));
    }

    static ShopActivity activity(ResultSet rs) throws SQLException {
        return new ShopActivity(
                rs.getInt("products"),
                rs.getObject("first_product_at", OffsetDateTime.class),
                rs.getInt("stock_changes"),
                rs.getObject("first_stock_change_at", OffsetDateTime.class),
                rs.getInt("counts"),
                rs.getInt("changes_last_7_days"),
                rs.getInt("active_days_first_week"),
                rs.getInt("low_stock"),
                rs.getInt("staff"),
                rs.getInt("list_files"));
    }
}
