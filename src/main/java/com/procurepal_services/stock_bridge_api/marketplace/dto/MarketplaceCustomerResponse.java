package com.procurepal_services.stock_bridge_api.marketplace.dto;

import com.procurepal_services.stock_bridge_api.entity.PaymentTerms;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A buying company, as ProcurePal sees it. {@code paymentTerms} is included because
 * it is the lever ops actually pulls on this screen - deciding whether a customer has
 * earned pay-on-delivery is the whole reason for looking at their order history.
 *
 * {@code lifetimeSpend} counts PAID orders only: money promised on a doorstep is not
 * money received, and a customers list that inflates itself with unsettled COD orders
 * is worse than no list.
 */
public record MarketplaceCustomerResponse(
        UUID clientId,
        String name,
        String slug,
        String phone,
        String email,
        PaymentTerms paymentTerms,
        boolean active,
        long orderCount,
        BigDecimal lifetimeSpend,
        OffsetDateTime lastOrderAt,
        OffsetDateTime customerSince) {
}
