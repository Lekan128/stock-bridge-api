package com.procurepal_services.stock_bridge_api.onboarding;

import com.procurepal_services.stock_bridge_api.founding.WhatsAppNumbers;
import com.procurepal_services.stock_bridge_api.onboarding.dto.FirstWeekReport;
import com.procurepal_services.stock_bridge_api.onboarding.dto.FirstWeekShop;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The team's view of every new shop's first week (LANDING_PAGE_PLAN.md §4): how far each has got,
 * which WhatsApp message is due, and the funnel. The messages themselves are sent by a person from
 * the WhatsApp Business app; this records that they were.
 */
@Service
@RequiredArgsConstructor
public class FirstWeekService {

    static final Set<String> KINDS = Set.of("WELCOME", "LOADED", "DAY_3", "DAY_7");

    private final NamedParameterJdbcTemplate jdbc;

    /** The window's clients: self-service buying companies, never the platform owner. */
    private static final String WINDOW = "c.client_type = 'COMPANY' AND NOT c.is_platform_owner"
            + " AND c.created_at > now() - make_interval(days => :days)";

    /**
     * @param search optional: part of a shop's name or Company ID
     */
    @Transactional(readOnly = true)
    public FirstWeekReport report(int days, int page, int size, String search) {
        int window = Math.max(1, Math.min(days, 90));
        int pageSize = Math.max(1, Math.min(size, 100));
        int pageNumber = Math.max(0, page);
        String term = search == null || search.isBlank() ? null : "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("days", window)
                .addValue("limit", pageSize)
                .addValue("offset", pageNumber * pageSize)
                .addValue("term", term);
        String matching = WINDOW + (term == null ? "" : " AND (lower(c.name) LIKE :term OR c.slug LIKE :term)");

        List<FirstWeekShop> shops = jdbc.query(
                "SELECT c.id, c.name, c.slug, c.phone, c.created_at,"
                        + " (SELECT u.phone FROM users u WHERE u.client_id = c.id AND u.is_root LIMIT 1) AS owner_phone,"
                        + " r.id AS request_id, r.status AS request_status, COALESCE(r.counts_as_founding, false) AS founding,"
                        + " (SELECT u.is_active FROM users u WHERE u.client_id = c.id AND u.username = '"
                        + SupportAccessService.USERNAME + "') AS support_active, "
                        + OnboardingQueries.ACTIVITY_COLUMNS
                        + " FROM clients c"
                        + " LEFT JOIN LATERAL (SELECT id, status, counts_as_founding FROM setup_requests s"
                        + "   WHERE s.client_id = c.id ORDER BY s.created_at DESC LIMIT 1) r ON true"
                        + " WHERE " + matching
                        + " ORDER BY c.created_at DESC LIMIT :limit OFFSET :offset",
                params,
                (rs, rowNum) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
                    ShopActivity activity = OnboardingQueries.activity(rs);
                    String phone = rs.getString("phone") != null ? rs.getString("phone") : rs.getString("owner_phone");
                    Object support = rs.getObject("support_active");
                    return new FirstWeekShop(
                            id,
                            rs.getString("name"),
                            rs.getString("slug"),
                            phone == null ? null : WhatsAppNumbers.normalise(phone).orElse(null),
                            createdAt,
                            rs.getObject("request_id", UUID.class),
                            rs.getString("request_status"),
                            rs.getBoolean("founding"),
                            activity,
                            activated(activity, createdAt),
                            activity.activeDaysFirstWeek() >= 5,
                            new LinkedHashMap<>(),
                            support == null ? null : (Boolean.TRUE.equals(support) ? "ON" : "OFF"));
                });

        if (!shops.isEmpty()) {
            Map<UUID, FirstWeekShop> byId = new HashMap<>();
            shops.forEach(shop -> byId.put(shop.clientId(), shop));
            jdbc.query(
                    "SELECT client_id, kind, sent_at FROM onboarding_messages WHERE client_id IN (:ids)",
                    new MapSqlParameterSource("ids", byId.keySet()),
                    rs -> {
                        byId.get(rs.getObject("client_id", UUID.class))
                                .messages()
                                .put(rs.getString("kind"), rs.getObject("sent_at", OffsetDateTime.class));
                    });
        }

        // The funnel over the whole window, set-based: one pass over products and stock changes,
        // however many shops there are.
        Map<String, Object> funnel = jdbc.queryForMap(
                "WITH w AS (SELECT c.id, c.created_at FROM clients c WHERE " + WINDOW + "),"
                        + " p AS (SELECT DISTINCT client_id FROM products WHERE is_active AND client_id IN (SELECT id FROM w)),"
                        + " m AS (SELECT m.client_id, min(COALESCE(m.occurred_at, m.created_at)) AS first_at,"
                        + "   count(DISTINCT (COALESCE(m.occurred_at, m.created_at) AT TIME ZONE 'Africa/Lagos')::date)"
                        + "     FILTER (WHERE COALESCE(m.occurred_at, m.created_at) < w.created_at + interval '7 days') AS days"
                        + "   FROM stock_movements m JOIN w ON w.id = m.client_id"
                        + "   LEFT JOIN users mu ON mu.id = m.created_by LEFT JOIN roles mr ON mr.id = mu.role_id"
                        + "   WHERE m.import_batch_id IS NULL AND (mr.name IS NULL OR mr.name <> '" + SupportAccessService.ROLE + "')"
                        + "   GROUP BY m.client_id)"
                        + " SELECT count(*) AS signups,"
                        + "   count(p.client_id) AS products_loaded,"
                        + "   count(*) FILTER (WHERE p.client_id IS NOT NULL AND m.first_at <= w.created_at + interval '72 hours') AS activated,"
                        + "   count(*) FILTER (WHERE w.created_at < now() - interval '7 days') AS habit_eligible,"
                        + "   count(*) FILTER (WHERE w.created_at < now() - interval '7 days' AND m.days >= 5) AS habit"
                        + " FROM w LEFT JOIN p ON p.client_id = w.id LEFT JOIN m ON m.client_id = w.id",
                params);
        Integer matches = term == null
                ? ((Number) funnel.get("signups")).intValue()
                : jdbc.queryForObject("SELECT count(*) FROM clients c WHERE " + matching, params, Integer.class);
        Integer requests = jdbc.queryForObject(
                "SELECT count(*) FROM setup_requests WHERE source <> 'app' AND created_at > now() - make_interval(days => :days)",
                params, Integer.class);
        return new FirstWeekReport(
                window,
                pageNumber,
                (int) Math.ceil((matches == null ? 0 : matches) / (double) pageSize),
                requests == null ? 0 : requests,
                ((Number) funnel.get("signups")).intValue(),
                ((Number) funnel.get("products_loaded")).intValue(),
                ((Number) funnel.get("activated")).intValue(),
                ((Number) funnel.get("habit_eligible")).intValue(),
                ((Number) funnel.get("habit")).intValue(),
                shops);
    }

    /** Products loaded, and the shop's own first stock change within 72 hours of signing up. */
    static boolean activated(ShopActivity activity, OffsetDateTime createdAt) {
        return activity.products() > 0
                && activity.firstStockChangeAt() != null
                && !activity.firstStockChangeAt().isAfter(createdAt.plus(Duration.ofHours(72)));
    }

    @Transactional
    public void markSent(UUID clientId, String kind, UUID superAdminId) {
        requireKind(kind);
        int updated = jdbc.update(
                "INSERT INTO onboarding_messages (client_id, kind, sent_at, sent_by)"
                        + " SELECT id, :kind, now(), :by FROM clients WHERE id = :clientId"
                        + " ON CONFLICT (client_id, kind) DO UPDATE SET sent_at = now(), sent_by = :by",
                new MapSqlParameterSource().addValue("clientId", clientId).addValue("kind", kind).addValue("by", superAdminId));
        if (updated == 0) {
            throw new ProductListException("That shop does not exist.");
        }
    }

    @Transactional
    public void unmark(UUID clientId, String kind) {
        requireKind(kind);
        jdbc.update("DELETE FROM onboarding_messages WHERE client_id = :clientId AND kind = :kind",
                Map.of("clientId", clientId, "kind", kind));
    }

    private static void requireKind(String kind) {
        if (!KINDS.contains(kind)) {
            throw new IllegalArgumentException("Unknown message: " + kind);
        }
    }
}
