package com.procurepal_services.stock_bridge_api.founding.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of the team's setup queue.
 *
 * @param founding    whether it took a founding place
 * @param clientId    the account the shop created, once it has (with its name and Company ID)
 * @param contactedAt when somebody first replied: the speed-to-lead clock stops here
 * @param listFiles   files the shop sent with "Send us your list" in the app (step 5)
 */
public record SetupRequestView(
        UUID id,
        String businessName,
        String whatsapp,
        String source,
        String status,
        boolean founding,
        UUID clientId,
        String clientName,
        String clientSlug,
        OffsetDateTime createdAt,
        OffsetDateTime contactedAt,
        String note,
        int listFiles) {
}
