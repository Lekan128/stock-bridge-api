package com.procurepal_services.stock_bridge_api.purchase;

import com.procurepal_services.stock_bridge_api.purchase.dto.PurchaseSource;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * What to search for, before pagination. {@code companyVendorId} narrows to one supplier - the
 * per-vendor purchase-history screen always sets it; the company-wide screen leaves it null.
 * {@code source} narrows to one of the two ledgers; null means both.
 */
record PurchaseHistoryFilter(
        UUID buyerClientId, UUID companyVendorId, PurchaseSource source, OffsetDateTime from, OffsetDateTime to) {}
