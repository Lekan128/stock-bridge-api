package com.procurepal_services.stock_bridge_api.order.dto;

import com.procurepal_services.stock_bridge_api.entity.Order;
import java.util.UUID;

/**
 * The shipping details as they were at checkout, read from the order's own columns
 * rather than through addressId. addressId is carried only so "reuse this address"
 * and per-location analytics can point back at the original row - editing or
 * deactivating that row must never rewrite where this order actually went.
 */
public record OrderDeliveryResponse(
        UUID addressId,
        String label,
        String contactName,
        String contactPhone,
        String addressLine1,
        String addressLine2,
        String city,
        String state,
        String landmark,
        String notes) {

    public static OrderDeliveryResponse from(Order order) {
        return new OrderDeliveryResponse(
                order.getDeliveryAddressId(),
                order.getDeliveryLabel(),
                order.getDeliveryContactName(),
                order.getDeliveryContactPhone(),
                order.getDeliveryAddressLine1(),
                order.getDeliveryAddressLine2(),
                order.getDeliveryCity(),
                order.getDeliveryState(),
                order.getDeliveryLandmark(),
                order.getDeliveryNotes());
    }
}
