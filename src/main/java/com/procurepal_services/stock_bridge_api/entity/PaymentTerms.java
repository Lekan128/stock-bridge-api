package com.procurepal_services.stock_bridge_api.entity;

/**
 * The commercial relationship ProcurePal has with a company
 * (clients.payment_terms). PREPAID is the default for every new signup: no
 * trading history means no credit.
 *
 * This gates only whether pay-on-delivery may be OFFERED at checkout. It is not
 * the only gate - see MarketplaceSettings.payOnDeliveryEnabled and
 * payOnDeliveryMaxOrderValue, both of which must also allow it.
 */
public enum PaymentTerms {

    PREPAID,
    PAY_ON_DELIVERY_ALLOWED;

    public boolean allowsPayOnDelivery() {
        return this == PAY_ON_DELIVERY_ALLOWED;
    }
}
