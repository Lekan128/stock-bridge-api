package com.procurepal_services.stock_bridge_api.companyvendor.dto;

import java.math.BigDecimal;

/**
 * {@code PATCH .../vendors/{vendorId}/packs/{packId}} body - patch semantics, every field
 * optional. Packaging (container/size) is deliberately not here - see
 * {@code ProductVendorService.updatePack}'s javadoc for why a pack's container/size is not
 * editable in place. {@code isDefault} is a swap when {@code true}, same convention
 * {@code UpdateProductVendorRequest.isPreferred} already uses one level up.
 */
public record UpdatePackRequest(String vendorSku, BigDecimal lastCostPrice, Boolean isDefault) {
}
