package com.procurepal_services.stock_bridge_api.order.dto;

import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.PaymentTerms;
import java.util.UUID;

/**
 * Who bought it. Present only on ProcurePal's fulfilment views and deliberately
 * absent (null, and therefore omitted by the non_null serializer) from the buyer's
 * own order responses - the buyer already knows who they are, and the field would
 * invite a frontend to render their own company as "the customer".
 */
public record OrderCustomerResponse(
        UUID clientId, String name, String slug, String phone, String email, PaymentTerms paymentTerms) {

    public static OrderCustomerResponse from(Client client) {
        return new OrderCustomerResponse(
                client.getId(),
                client.getName(),
                client.getSlug(),
                client.getPhone(),
                client.getAdminContactEmail(),
                client.getPaymentTerms());
    }
}
