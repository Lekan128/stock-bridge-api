package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.ProductPriceChange;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductPriceChangeRepository extends JpaRepository<ProductPriceChange, UUID> {

    /** Newest first. Scoped by client in the query, not by the session filter. */
    List<ProductPriceChange> findAllByClientIdAndProductIdOrderByChangedAtDesc(UUID clientId, UUID productId);
}
