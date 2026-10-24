package com.procurepal_services.stock_bridge_api.analytics;

import com.procurepal_services.stock_bridge_api.analytics.dto.BranchTableResponse;
import com.procurepal_services.stock_bridge_api.email.EmailNotificationService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The owner's daily digest (MULTI_BRANCH_UX_PLAN.md section 5.13), only for people who turn it on:
 * one email each evening with the branch table. Not a reminder - nothing in it repeats until acted
 * on; it is the day's figures, once (the email rules: one email per event per reader).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BranchDigestService {

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    private final NamedParameterJdbcTemplate jdbc;
    private final BranchTableService branchTableService;
    private final EmailNotificationService emailNotificationService;

    @Value("${app.branches.digest.enabled:true}")
    private boolean enabled;

    /** Turns the digest on or off for this person; returns what it is now. */
    @Transactional
    public boolean set(UUID userId, boolean on) {
        jdbc.update("UPDATE users SET branch_digest = :on WHERE id = :id",
                new MapSqlParameterSource().addValue("on", on).addValue("id", userId));
        return on;
    }

    @Transactional(readOnly = true)
    public boolean isOn(UUID userId) {
        return Boolean.TRUE.equals(jdbc.query("SELECT branch_digest FROM users WHERE id = :id",
                new MapSqlParameterSource("id", userId), rs -> rs.next() ? rs.getBoolean(1) : null));
    }

    /** 7 pm in Lagos, when the day's trade is mostly done. */
    @Scheduled(cron = "${app.branches.digest.cron:0 0 19 * * *}", zone = "Africa/Lagos")
    public void scheduledSend() {
        if (!enabled) {
            return;
        }
        try {
            int sent = sendDue(LocalDate.now(LAGOS));
            if (sent > 0) {
                log.info("Sent {} branch digest(s)", sent);
            }
        } catch (RuntimeException ex) {
            log.error("Branch digest run failed; tomorrow's will send as usual", ex);
        }
    }

    /**
     * Everyone due a digest today: turned on, active, at a company with two branches, and still holding
     * the reports permission. Each is claimed before sending, so two servers never send one twice.
     *
     * @return how many were sent.
     */
    public int sendDue(LocalDate today) {
        List<UUID> due = jdbc.queryForList("""
                SELECT u.id FROM users u
                WHERE u.branch_digest AND u.is_active
                  AND (u.branch_digest_sent_on IS NULL OR u.branch_digest_sent_on < :today)
                  AND (SELECT COUNT(*) FROM branches b WHERE b.client_id = u.client_id) > 1
                  AND (u.is_root OR EXISTS (
                        SELECT 1 FROM role_permissions rp JOIN permissions p ON p.id = rp.permission_id
                        WHERE rp.role_id = u.role_id AND p.code = 'VIEW_ANALYTICS'))
                """, new MapSqlParameterSource("today", today), UUID.class);
        int sent = 0;
        for (UUID userId : due) {
            int claimed = jdbc.update("""
                    UPDATE users SET branch_digest_sent_on = :today
                    WHERE id = :id AND (branch_digest_sent_on IS NULL OR branch_digest_sent_on < :today)
                    """, new MapSqlParameterSource().addValue("today", today).addValue("id", userId));
            if (claimed == 0) {
                continue;
            }
            try {
                UUID clientId = jdbc.queryForObject("SELECT client_id FROM users WHERE id = :id",
                        new MapSqlParameterSource("id", userId), UUID.class);
                BranchTableResponse table = branchTableService.table(clientId, userId);
                emailNotificationService.branchDigest(userId, clientId, table);
                sent++;
            } catch (RuntimeException ex) {
                log.warn("Branch digest for user {} not sent", userId, ex);
            }
        }
        return sent;
    }
}
