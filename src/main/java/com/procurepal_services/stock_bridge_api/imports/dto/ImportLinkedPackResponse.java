package com.procurepal_services.stock_bridge_api.imports.dto;

import java.util.UUID;

/**
 * One row of {@code GET /api/imports/{id}/linked-packs} - a pack this session's review screen
 * confirmed into existence, offered back before a discard throws the session away, so "nothing
 * will change in your catalog" (the plain-discard copy) stays true rather than silently false.
 * See V25's migration comment on {@code product_vendor_packs.created_from_import_session_id} for
 * why this pack outlives the session that proposed it unless someone chooses to remove it here.
 */
public record ImportLinkedPackResponse(UUID packId, String productName, String vendorName, String packLabel) {
}
