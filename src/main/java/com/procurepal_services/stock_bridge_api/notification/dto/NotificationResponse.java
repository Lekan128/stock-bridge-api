package com.procurepal_services.stock_bridge_api.notification.dto;

import com.procurepal_services.stock_bridge_api.entity.Notification;
import com.procurepal_services.stock_bridge_api.entity.NotificationType;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Mirrors {@code stock-bridge-ui/src/features/notifications/types.ts} (ServerNotification). */
public record NotificationResponse(
        UUID id,
        NotificationType type,
        String title,
        String body,
        String link,
        UUID orderId,
        OffsetDateTime readAt,
        OffsetDateTime createdAt) {

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getType(),
                notification.getTitle(),
                notification.getBody(),
                notification.getLink(),
                notification.getOrderId(),
                notification.getReadAt(),
                notification.getCreatedAt());
    }
}
