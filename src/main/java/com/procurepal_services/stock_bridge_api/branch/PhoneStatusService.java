package com.procurepal_services.stock_bridge_api.branch;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What each phone last said it had waiting to send (V55, MULTI_BRANCH_UX_PLAN.md section 5.8's
 * "Phones"). Reported on the catalogue's change feed - the request a phone makes anyway - so it costs
 * no extra round trip on 3G. A branch it doesn't work at, or a branch of another company, is stored
 * as no branch rather than refused: this is a report, and the feed must never fail because of it.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class PhoneStatusService {

    private final NamedParameterJdbcTemplate jdbc;
    private final BranchAccessService branchAccessService;

    public void report(UUID clientId, UUID userId, UUID deviceId, UUID branchId, int waiting, int needsYou, Long oldestWaitingMs) {
        if (deviceId == null || userId == null) {
            return;
        }
        UUID branch = branchId != null && branchAccessService.viewBranchIds(userId).contains(branchId) ? branchId : null;
        OffsetDateTime oldest = oldestWaitingMs == null || waiting + needsYou == 0
                ? null
                : Instant.ofEpochMilli(oldestWaitingMs).atOffset(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO phone_sync_status (user_id, device_id, client_id, branch_id, waiting_count, needs_you_count,
                                               oldest_waiting_at, last_seen_at)
                VALUES (:userId, :deviceId, :clientId, :branchId, :waiting, :needsYou, :oldest, now())
                ON CONFLICT (user_id, device_id) DO UPDATE SET
                    branch_id = EXCLUDED.branch_id, waiting_count = EXCLUDED.waiting_count,
                    needs_you_count = EXCLUDED.needs_you_count, oldest_waiting_at = EXCLUDED.oldest_waiting_at,
                    last_seen_at = now()
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("deviceId", deviceId)
                .addValue("clientId", clientId)
                .addValue("branchId", branch)
                .addValue("waiting", Math.max(0, waiting))
                .addValue("needsYou", Math.max(0, needsYou))
                .addValue("oldest", oldest));
    }
}
