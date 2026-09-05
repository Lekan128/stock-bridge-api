package com.procurepal_services.stock_bridge_api.companyvendor.dto;

import java.math.BigDecimal;

/**
 * {@code POST .../vendors/{vendorId}/packs} body. {@code packagingUnit}/{@code packagingSize}
 * null together means the bare stock unit; the pairing and sign rules are cross-field, so they
 * stay in {@code ProductVendorService.addPack} ({@code InvalidProductVendorPackException}) rather
 * than being duplicated as bean-validation annotations.
 */
public record AddPackRequest(
        String packagingUnit, BigDecimal packagingSize, String vendorSku, BigDecimal lastCostPrice) {
}
