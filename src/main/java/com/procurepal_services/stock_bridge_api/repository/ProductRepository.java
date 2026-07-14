package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.Product;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends TenantScopedRepository<Product, UUID>, JpaSpecificationExecutor<Product> {

    Optional<Product> findByClientIdAndSku(UUID clientId, String sku);

    long countByClientIdAndActive(UUID clientId, boolean active);

    long countByClientId(UUID clientId);

    @Query("SELECT p FROM Product p WHERE p.clientId = :clientId AND p.active = true "
            + "AND p.lowStockThreshold IS NOT NULL AND p.quantityOnHand <= p.lowStockThreshold")
    List<Product> findLowStockByClientId(@Param("clientId") UUID clientId);

    /** Same predicate as findLowStockByClientId, as a count - avoids loading entities just for /analytics/summary. */
    @Query("SELECT COUNT(p) FROM Product p WHERE p.clientId = :clientId AND p.active = true "
            + "AND p.lowStockThreshold IS NOT NULL AND p.quantityOnHand <= p.lowStockThreshold")
    long countLowStockByClientId(@Param("clientId") UUID clientId);

    /**
     * Row-locks the product for the duration of the caller's transaction, so
     * two concurrent stock movements against the same product serialize
     * instead of racing on a read-modify-write of quantity_on_hand - see
     * StockManagementService for why a pessimistic lock was chosen over an
     * atomic UPDATE ... WHERE quantity_on_hand >= ? for this.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id AND p.clientId = :clientId")
    Optional<Product> findByIdAndClientIdForUpdate(@Param("id") UUID id, @Param("clientId") UUID clientId);
}
