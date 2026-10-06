package com.procurepal_services.stock_bridge_api.founding;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The founding offer's real limits (LANDING_PAGE_PLAN.md §1; owners' decisions of 2026-10-05):
 * 100 founding setups, 10 a week because people do them, and the offer ends the day paid plans
 * start. The landing page shows numbers derived from these and the setup_requests table, never
 * numbers typed into the page.
 *
 * @param total          founding places in all
 * @param weeklyCapacity setups the team can do in one week
 * @param endsOn         the first day the offer is closed (paid plans start)
 * @param zone           whose Monday a week starts on
 * @param submitLimit    setup requests one caller may make per window
 * @param submitWindow   the rolling window for {@code submitLimit}
 * @param duplicateWindow a second request for the same WhatsApp number within this long is the
 *                        same request, not a second founding place
 * @param alertWebhookUrl optional. Every new setup request is also POSTed here as
 *                        {@code {"text": "..."}}, the shape Slack, Google Chat and Mattermost
 *                        incoming webhooks accept, so the team's phones buzz within seconds
 *                        (speed to lead, plan rule 5). The email to the support inbox is sent
 *                        either way; email alone is too slow to answer within 5 minutes.
 */
@ConfigurationProperties(prefix = "app.founding")
public record FoundingOfferProperties(
        Integer total,
        Integer weeklyCapacity,
        LocalDate endsOn,
        ZoneId zone,
        Integer submitLimit,
        Duration submitWindow,
        Duration duplicateWindow,
        String alertWebhookUrl) {

    public FoundingOfferProperties {
        total = total == null || total < 0 ? 100 : total;
        weeklyCapacity = weeklyCapacity == null || weeklyCapacity < 1 ? 10 : weeklyCapacity;
        endsOn = endsOn == null ? LocalDate.of(2027, 2, 1) : endsOn;
        zone = zone == null ? ZoneId.of("Africa/Lagos") : zone;
        submitLimit = submitLimit == null || submitLimit < 1 ? 5 : submitLimit;
        submitWindow = submitWindow == null || submitWindow.isZero() || submitWindow.isNegative() ? Duration.ofHours(1) : submitWindow;
        duplicateWindow = duplicateWindow == null || duplicateWindow.isNegative() ? Duration.ofDays(30) : duplicateWindow;
        alertWebhookUrl = alertWebhookUrl == null || alertWebhookUrl.isBlank() ? null : alertWebhookUrl.trim();
    }
}
