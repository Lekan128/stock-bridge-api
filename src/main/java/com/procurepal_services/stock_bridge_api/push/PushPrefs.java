package com.procurepal_services.stock_bridge_api.push;

import com.procurepal_services.stock_bridge_api.branch.BranchAccessService;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Each person's own notifications, per kind (V104; owner, 2026-11-19): "New tickets" for the areas they work in, and
 * "Ready for my tables". Until they choose, staff get them and people who record everywhere (managers, the owner)
 * don't, so a busy owner's phone doesn't buzz for every plate.
 */
@Service
@RequiredArgsConstructor
public class PushPrefs {

    public static final String TICKETS_NEW = "TICKETS_NEW";
    public static final String TICKETS_READY = "TICKETS_READY";
    private static final Set<String> KINDS = Set.of(TICKETS_NEW, TICKETS_READY);

    private final NamedParameterJdbcTemplate jdbc;
    private final BranchAccessService branchAccessService;

    /** This person's choice for each kind, or the default. */
    public Map<String, Boolean> of(UUID userId) {
        boolean byDefault = !branchAccessService.recordsEverywhere(userId);
        Map<String, Boolean> chosen = new HashMap<>();
        for (String kind : KINDS) chosen.put(kind, byDefault);
        jdbc.query("SELECT kind, is_on FROM push_prefs WHERE user_id = :userId", new MapSqlParameterSource("userId", userId),
                rs -> {
                    chosen.put(rs.getString("kind"), rs.getBoolean("is_on"));
                });
        return chosen;
    }

    public void set(UUID userId, String kind, boolean on) {
        if (!KINDS.contains(kind)) {
            throw new IllegalArgumentException("kind must be one of " + KINDS);
        }
        jdbc.update("""
                INSERT INTO push_prefs (user_id, kind, is_on) VALUES (:userId, :kind, :on)
                ON CONFLICT (user_id, kind) DO UPDATE SET is_on = EXCLUDED.is_on
                """, new MapSqlParameterSource().addValue("userId", userId).addValue("kind", kind).addValue("on", on));
    }

    /** Of these people, those who want {@code kind}. */
    public List<UUID> wanting(Collection<UUID> userIds, String kind) {
        return userIds.stream().distinct().filter(user -> Boolean.TRUE.equals(of(user).get(kind))).toList();
    }
}
