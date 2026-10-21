package com.procurepal_services.stock_bridge_api.selling.dto;

import java.util.List;
import java.util.UUID;

/**
 * Which old prices to adopt as selling prices: the given products, or every one still waiting when
 * {@code all} is true. Products that have a selling price by now are left alone either way.
 */
public record AdoptLegacyPricesRequest(List<UUID> productIds, Boolean all) {
}
