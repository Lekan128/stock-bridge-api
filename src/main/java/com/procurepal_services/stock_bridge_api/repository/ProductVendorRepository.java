package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.ProductVendor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The buyer-side product<->vendor join, tenant-scoped via the inherited {@code client_id} - see
 * {@code ProductVendor}'s class javadoc. Every finder here takes {@code clientId} explicitly,
 * the same belt-and-braces rule {@code ProductRepository}/{@code CompanyVendorRepository}
 * follow: isolation holds even if the Hibernate tenant filter is ever left disabled.
 */
public interface ProductVendorRepository extends JpaRepository<ProductVendor, UUID> {

    /**
     * The Vendors tab's own read: every supplier line for one product, preferred vendor first
     * (then oldest-added), matching {@code idx_product_vendors_product_id}'s column order.
     */
    @Query("SELECT pv FROM ProductVendor pv WHERE pv.clientId = :clientId AND pv.product.id = :productId "
            + "ORDER BY pv.isPreferred DESC, pv.createdAt ASC")
    List<ProductVendor> findAllByClientIdAndProductId(
            @Param("clientId") UUID clientId, @Param("productId") UUID productId);

    /** One vendor line, scoped to both the tenant and the product it must belong to. */
    Optional<ProductVendor> findByClientIdAndIdAndProductId(UUID clientId, UUID id, UUID productId);

    /**
     * The find-or-create key: is there already a line for this (product, vendor) pair. Used by
     * {@code StockManagementService.stockIn} and the {@code initialVendor} path on product
     * creation - see {@code companyvendor.ProductVendorService.findOrCreateForReceipt}.
     */
    Optional<ProductVendor> findByClientIdAndProductIdAndCompanyVendorId(
            UUID clientId, UUID productId, UUID companyVendorId);

    /**
     * Whichever line currently holds {@code isPreferred = true} for this product, if any - the
     * row a preferred-swap must unflip. See {@code ProductVendorService.update}.
     */
    Optional<ProductVendor> findByClientIdAndProductIdAndIsPreferredTrue(UUID clientId, UUID productId);

    /**
     * How many vendor lines this product already has. Zero means the next one created is
     * automatically preferred - the "first vendor wins" rule in
     * MULTI_VENDOR_INVENTORY_DESIGN.md section 5.1/7.3.
     */
    long countByClientIdAndProductId(UUID clientId, UUID productId);

    /**
     * The preferred vendor's line for every product in one page, in a single query with its
     * {@code companyVendor} eagerly joined - what {@code ProductManagementService.list}
     * populates {@code ProductResponse.preferredVendorName} from. This is the batched
     * counterpart to {@link #findByClientIdAndProductIdAndIsPreferredTrue}: fetching the
     * preferred vendor per-row for a whole page via that method (or via {@code
     * Product.getPreferredVendor()}'s lazy association) would be exactly the N+1 {@code
     * Product.vendors}' javadoc warns against.
     */
    @Query("SELECT pv FROM ProductVendor pv JOIN FETCH pv.companyVendor WHERE pv.clientId = :clientId "
            + "AND pv.product.id IN :productIds AND pv.isPreferred = true")
    List<ProductVendor> findPreferredByClientIdAndProductIdIn(
            @Param("clientId") UUID clientId, @Param("productIds") List<UUID> productIds);

    /**
     * "What do we buy from this supplier" - the population behind
     * {@code VendorPurchaseService.suppliedProducts}, replacing the pre-V19 query keyed on
     * {@code products.company_vendor_id}. Inactive products excluded, matching that method's
     * original reasoning: a deactivated product is not something the vendor detail screen
     * should still be offering as "supplied by this vendor".
     */
    @Query("SELECT pv FROM ProductVendor pv WHERE pv.clientId = :clientId AND pv.companyVendor.id = :companyVendorId "
            + "AND pv.product.active = true ORDER BY pv.product.name ASC")
    List<ProductVendor> findAllByClientIdAndCompanyVendorIdAndProductActive(
            @Param("clientId") UUID clientId, @Param("companyVendorId") UUID companyVendorId);
}
