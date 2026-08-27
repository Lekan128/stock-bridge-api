package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.ProductVendorPriceTier;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@code ProductVendorPriceTier} has no {@code client_id} of its own - see its class javadoc -
 * so every finder here is reached through {@code productVendorId}, itself already resolved
 * against the caller's tenant by {@code ProductVendorRepository} before this is ever queried.
 * There is no lookup by tier id alone with no product_vendor_id alongside it, on purpose.
 */
public interface ProductVendorPriceTierRepository extends JpaRepository<ProductVendorPriceTier, UUID> {

    /** The expanded Vendors-tab row: this vendor's price breaks, cheapest quantity first. */
    List<ProductVendorPriceTier> findAllByProductVendorIdOrderByMinQuantityAsc(UUID productVendorId);

    Optional<ProductVendorPriceTier> findByIdAndProductVendorId(UUID id, UUID productVendorId);

    /**
     * Every tier across every vendor of one product, ordered so "cheapest applicable price at
     * this quantity" can be resolved with a single linear scan (highest {@code minQuantity} not
     * exceeding the requested quantity wins) - see {@code ProductVendorService.cheaperVendorHint}.
     * Written as an explicit JPQL join rather than a derived two-level-nested-property method
     * name - explicit is how this codebase writes anything past one hop, matching
     * {@code ProductVendorRepository}'s own queries.
     */
    @Query("SELECT t FROM ProductVendorPriceTier t WHERE t.productVendor.product.id = :productId "
            + "ORDER BY t.minQuantity ASC")
    List<ProductVendorPriceTier> findAllByProductVendorProductIdOrderByMinQuantityAsc(@Param("productId") UUID productId);

    /** Rejects a duplicate breakpoint on the same vendor line before the DB's own UNIQUE does. */
    boolean existsByProductVendorIdAndMinQuantity(UUID productVendorId, BigDecimal minQuantity);
}
