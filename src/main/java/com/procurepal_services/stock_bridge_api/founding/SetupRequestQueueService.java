package com.procurepal_services.stock_bridge_api.founding;

import com.procurepal_services.stock_bridge_api.founding.dto.SetupRequestCounts;
import com.procurepal_services.stock_bridge_api.founding.dto.SetupRequestView;
import com.procurepal_services.stock_bridge_api.founding.dto.UpdateSetupRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The team's queue of setup requests (LANDING_PAGE_PLAN.md, step 4), for super admins.
 *
 * <p>A status tab is a queue and reads oldest first: the shop that has waited longest is next. The
 * All tab is a history and reads newest first. The vendor waitlist does the same, for the same
 * reason.
 */
@Service
@RequiredArgsConstructor
public class SetupRequestQueueService {

    private static final String SELECT = "SELECT r.id, r.business_name, r.whatsapp, r.source, r.status,"
            + " r.counts_as_founding, r.client_id, c.name AS client_name, c.slug AS client_slug,"
            + " r.created_at, r.contacted_at, r.note,"
            + " (SELECT count(*) FROM product_list_files f WHERE f.client_id = r.client_id) AS list_files"
            + " FROM setup_requests r LEFT JOIN clients c ON c.id = r.client_id";

    /**
     * Waiting is "nobody has replied yet", whatever the status: a shop can send its list from the
     * app before anybody has answered it, and it still needs that answer.
     */
    private static final Map<String, String> TABS = Map.of(
            "NEW", "r.contacted_at IS NULL AND r.status NOT IN ('RUNNING', 'NOT_A_FIT')",
            "IN_PROGRESS", "r.contacted_at IS NOT NULL AND r.status IN ('NEW', 'CONTACTED', 'LIST_RECEIVED', 'LOADED')",
            "RUNNING", "r.status = 'RUNNING'",
            "NOT_A_FIT", "r.status = 'NOT_A_FIT'");

    private final NamedParameterJdbcTemplate jdbc;

    /** @param tab NEW, IN_PROGRESS, RUNNING or NOT_A_FIT; null for everything. */
    @Transactional(readOnly = true)
    public Page<SetupRequestView> list(String tab, Pageable pageable) {
        String condition = tab == null ? null : TABS.get(tab);
        if (tab != null && condition == null) {
            throw new IllegalArgumentException("Unknown tab: " + tab);
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", pageable.getPageSize())
                .addValue("offset", pageable.getOffset());
        String where = condition == null ? "" : " WHERE " + condition;
        String order = condition == null ? " ORDER BY r.created_at DESC" : " ORDER BY r.created_at ASC";
        List<SetupRequestView> rows = jdbc.query(SELECT + where + order + " LIMIT :limit OFFSET :offset", params, this::view);
        Long total = jdbc.queryForObject("SELECT count(*) FROM setup_requests r" + where, params, Long.class);
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    @Transactional(readOnly = true)
    public SetupRequestCounts counts() {
        return jdbc.queryForObject(
                "SELECT count(*) FILTER (WHERE " + TABS.get("NEW") + ") AS waiting,"
                        + " count(*) FILTER (WHERE " + TABS.get("IN_PROGRESS") + ") AS in_progress,"
                        + " count(*) FILTER (WHERE " + TABS.get("RUNNING") + ") AS running,"
                        + " count(*) FILTER (WHERE " + TABS.get("NOT_A_FIT") + ") AS not_a_fit,"
                        + " count(*) AS all_requests,"
                        + " (SELECT round(percentile_cont(0.5) WITHIN GROUP ("
                        + "     ORDER BY extract(epoch FROM contacted_at - created_at) / 60))"
                        + "   FROM setup_requests WHERE contacted_at IS NOT NULL"
                        + "   AND created_at > now() - interval '30 days') AS median_minutes"
                        + " FROM setup_requests r",
                Map.of(),
                (rs, rowNum) -> new SetupRequestCounts(
                        rs.getInt("waiting"),
                        rs.getInt("in_progress"),
                        rs.getInt("running"),
                        rs.getInt("not_a_fit"),
                        rs.getInt("all_requests"),
                        rs.getObject("median_minutes") == null ? null : rs.getInt("median_minutes")));
    }

    /**
     * Moves a request along. The first move out of NEW stamps contacted_at, and nothing later
     * changes it: that is the reply time, and a request moved back to NEW was still answered.
     */
    @Transactional
    public SetupRequestView update(UUID id, UpdateSetupRequest request) {
        int updated = jdbc.update(
                "UPDATE setup_requests SET status = COALESCE(CAST(:status AS VARCHAR), status), updated_at = now(),"
                        + " contacted_at = CASE WHEN contacted_at IS NULL"
                        + "   AND (:contacted OR COALESCE(CAST(:status AS VARCHAR), 'NEW') <> 'NEW') THEN now() ELSE contacted_at END,"
                        + " note = CASE WHEN :hasNote THEN CAST(:note AS VARCHAR) ELSE note END"
                        + " WHERE id = :id",
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("status", request.status())
                        .addValue("contacted", Boolean.TRUE.equals(request.contacted()))
                        .addValue("hasNote", request.note() != null)
                        .addValue("note", request.note() == null || request.note().isBlank() ? null : request.note().trim()));
        if (updated == 0) {
            throw new SetupRequestNotFoundException();
        }
        return jdbc.query(SELECT + " WHERE r.id = :id", Map.of("id", id), this::view).getFirst();
    }

    private SetupRequestView view(ResultSet rs, int rowNum) throws SQLException {
        return new SetupRequestView(
                rs.getObject("id", UUID.class),
                rs.getString("business_name"),
                rs.getString("whatsapp"),
                rs.getString("source"),
                rs.getString("status"),
                rs.getBoolean("counts_as_founding"),
                rs.getObject("client_id", UUID.class),
                rs.getString("client_name"),
                rs.getString("client_slug"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("contacted_at", OffsetDateTime.class),
                rs.getString("note"),
                rs.getInt("list_files"));
    }
}
