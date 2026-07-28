package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.CartItem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

/**
 * Plain JpaRepository: CartItem has no client_id and is not tenant-scoped (see
 * that entity for why). Isolation therefore depends entirely on the caller
 * resolving the cart through CartRepository - which IS tenant-scoped - first, and
 * only then passing that cart's id in here. Never accept a cartId from a request
 * body.
 *
 * Note there is deliberately no JOIN FETCH of the catalog product: productId
 * points at the platform owner's product while the request runs under the buyer's
 * tenant filter, so a fetch join would match nothing. Load catalog products
 * separately via ProductRepository's marketplace finders.
 */
public interface CartItemRepository extends JpaRepository<CartItem, UUID> {

    List<CartItem> findAllByCartIdOrderByCreatedAtAsc(UUID cartId);

    Optional<CartItem> findByCartIdAndProductId(UUID cartId, UUID productId);

    long countByCartId(UUID cartId);

    @Modifying
    void deleteByCartId(UUID cartId);

    @Modifying
    void deleteByCartIdAndProductId(UUID cartId, UUID productId);

    /**
     * Cleanup hook for the marketplace-admin side: unlisting or deleting a catalog
     * product should not leave unbuyable lines sitting in every buyer's cart.
     */
    @Modifying
    void deleteByProductId(UUID productId);
}
