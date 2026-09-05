package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.ProductVendorPack;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@code ProductVendorPack} has no {@code client_id} of its own - see its class javadoc - so
 * every finder here is reached through {@code productVendorId}, itself already resolved against
 * the caller's tenant by {@code ProductVendorRepository} before this is ever queried. There is
 * no lookup by pack id alone with no product_vendor_id alongside it, on purpose.
 */
public interface ProductVendorPackRepository extends JpaRepository<ProductVendorPack, UUID> {

    /** The expanded Vendors-tab row: this vendor's packs, default first, then oldest-added. */
    List<ProductVendorPack> findAllByProductVendorIdOrderByIsDefaultDescCreatedAtAsc(UUID productVendorId);

    Optional<ProductVendorPack> findByIdAndProductVendorId(UUID id, UUID productVendorId);

    Optional<ProductVendorPack> findByProductVendorIdAndIsDefaultTrue(UUID productVendorId);

    /** Batched counterpart of {@link #findByProductVendorIdAndIsDefaultTrue} for a page of vendor
     *  lines at once - the same N+1-avoidance {@code ProductVendorRepository
     *  .findPreferredByClientIdAndProductIdIn} already documents for itself. */
    List<ProductVendorPack> findAllByProductVendorIdInAndIsDefaultTrue(List<UUID> productVendorIds);

    /** Batched counterpart of {@link #findAllByProductVendorIdOrderByIsDefaultDescCreatedAtAsc}
     *  for a page of vendor lines at once - same N+1-avoidance reasoning. */
    List<ProductVendorPack> findAllByProductVendorIdInOrderByIsDefaultDescCreatedAtAsc(List<UUID> productVendorIds);

    /** The receipt-path lookup for a real pack - see {@code ProductVendorService.findOrCreateForReceipt}. */
    Optional<ProductVendorPack> findByProductVendorIdAndPackagingUnitAndPackagingSize(
            UUID productVendorId, String packagingUnit, BigDecimal packagingSize);

    /** The receipt-path lookup for the bare-stock-unit case. A plain equality query cannot express
     *  "packaging_unit IS NULL" - {@code = NULL} is never true in SQL - hence the separate method. */
    Optional<ProductVendorPack> findByProductVendorIdAndPackagingUnitIsNull(UUID productVendorId);

    long countByProductVendorId(UUID productVendorId);

    /**
     * Every pack across every vendor of one product - what
     * {@code product.unit.UnitOptions.forProductAndSupplier}'s multi-pack overload needs to build
     * a supplier-scoped unit set, and what {@code ProductVendorResponse.from} batch-loads so
     * listing a product's vendors costs one extra query total, not one per row. Written as an
     * explicit JPQL join rather than a derived two-level-nested-property method name, matching
     * {@code ProductVendorRepository}'s own queries.
     */
    @Query("SELECT p FROM ProductVendorPack p WHERE p.productVendor.product.id = :productId "
            + "ORDER BY p.isDefault DESC, p.createdAt ASC")
    List<ProductVendorPack> findAllByProductVendorProductIdOrderByIsDefaultDescCreatedAtAsc(
            @Param("productId") UUID productId);

    /** Rejects a duplicate (container, size) pack on the same vendor line before the DB's own UNIQUE does. */
    boolean existsByProductVendorIdAndPackagingUnitAndPackagingSize(
            UUID productVendorId, String packagingUnit, BigDecimal packagingSize);

    /** Rejects a second bare-stock-unit ("no container") pack on the same vendor line. */
    boolean existsByProductVendorIdAndPackagingUnitIsNull(UUID productVendorId);

    /**
     * What a discard needs to know before it deletes an import session: every pack that session's
     * review screen confirmed into existence, so it can offer to take them with it - V25,
     * {@code ImportSessionService.linkedPacks}/{@code discard}.
     */
    List<ProductVendorPack> findAllByCreatedFromImportSessionId(UUID importSessionId);
}
