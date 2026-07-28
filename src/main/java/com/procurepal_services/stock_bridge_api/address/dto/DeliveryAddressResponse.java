package com.procurepal_services.stock_bridge_api.address.dto;

import com.procurepal_services.stock_bridge_api.entity.Branch;
import com.procurepal_services.stock_bridge_api.entity.DeliveryAddress;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Named {@code isDefault} on the wire even though the entity field is
 * {@code defaultAddress} ({@code default} is a Java keyword) - the JSON is what the
 * address picker reads, and it should read the way the column does.
 */
public record DeliveryAddressResponse(
        UUID id,
        String label,
        String contactName,
        String contactPhone,
        String addressLine1,
        String addressLine2,
        String city,
        String state,
        String landmark,
        String deliveryNotes,
        UUID branchId,
        String branchName,
        boolean isDefault,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static DeliveryAddressResponse from(DeliveryAddress address) {
        Branch branch = address.getBranch();
        return new DeliveryAddressResponse(
                address.getId(),
                address.getLabel(),
                address.getContactName(),
                address.getContactPhone(),
                address.getAddressLine1(),
                address.getAddressLine2(),
                address.getCity(),
                address.getState(),
                address.getLandmark(),
                address.getDeliveryNotes(),
                branch == null ? null : branch.getId(),
                branch == null ? null : branch.getName(),
                address.isDefaultAddress(),
                address.isActive(),
                address.getCreatedAt(),
                address.getUpdatedAt());
    }
}
