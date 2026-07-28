package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.ProductCategory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Plain JpaRepository, not TenantScopedRepository: categories are global and
 * ProcurePal-managed (see ProductCategory). Writes are gated by
 * PlatformOwnerGuard at the controller, not by tenant scoping.
 */
public interface ProductCategoryRepository extends JpaRepository<ProductCategory, UUID> {

    Optional<ProductCategory> findBySlug(String slug);

    boolean existsBySlug(String slug);

    /** What the public storefront's category filter serves. */
    List<ProductCategory> findAllByActiveTrueOrderBySortOrderAscNameAsc();

    /** Admin view: inactive categories still have to be manageable. */
    List<ProductCategory> findAllByOrderBySortOrderAscNameAsc();

    List<ProductCategory> findAllByParentIdOrderBySortOrderAscNameAsc(UUID parentId);

    long countByParentId(UUID parentId);
}
