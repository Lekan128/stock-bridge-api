package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.Cart;
import java.util.Optional;
import java.util.UUID;

public interface CartRepository extends TenantScopedRepository<Cart, UUID> {

    /**
     * At most one cart exists per company (unique constraint on client_id), so
     * this is the entry point for the whole cart feature: find-or-create it here,
     * then work through CartItemRepository.
     */
    Optional<Cart> findByClientId(UUID clientId);
}
