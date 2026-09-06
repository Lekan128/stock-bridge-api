package com.procurepal_services.stock_bridge_api.purchase;

import com.procurepal_services.stock_bridge_api.purchase.dto.PurchaseSource;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of the merged, paginated {@code orders} + {@code stock_movements} query - just enough
 * to say WHICH record this is and WHEN it happened, in the correct global order. Everything else
 * about the purchase is hydrated afterwards, batched by {@link #source}, in
 * {@link PurchaseHistoryService}.
 */
record PurchasePointer(UUID id, PurchaseSource source, OffsetDateTime occurredAt, UUID companyVendorId) {}
