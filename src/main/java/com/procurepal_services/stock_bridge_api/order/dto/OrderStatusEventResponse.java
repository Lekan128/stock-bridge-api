package com.procurepal_services.stock_bridge_api.order.dto;

import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.OrderStatusEvent;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One step of the tracking timeline. fromStatus is null for the order's creation
 * event; createdBy is null for a system transition (a verified payment, the
 * abandoned-checkout sweep) rather than being attributed to a fake actor.
 */
public record OrderStatusEventResponse(
        UUID id, OrderStatus fromStatus, OrderStatus toStatus, String note, UUID createdBy, OffsetDateTime createdAt) {

    public static OrderStatusEventResponse from(OrderStatusEvent event) {
        return new OrderStatusEventResponse(
                event.getId(),
                event.getFromStatus(),
                event.getToStatus(),
                event.getNote(),
                event.getCreatedBy(),
                event.getCreatedAt());
    }
}
