package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.Product;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    // ------------------------------------------------------------------------
    // Marketplace.
    //
    // Every finder below takes clientId explicitly, including the public
    // storefront ones. That is not redundancy: the public catalog endpoints run
    // with NO TenantContext, so the Hibernate tenant filter is disabled and
    // there is nothing else stopping a query from returning another tenant's
    // rows. Passing the platform owner's client_id, on top of the
    // marketplaceListed/active predicates, is what makes the public catalog safe.
    // ------------------------------------------------------------------------

    Optional<Product> findByClientIdAndSlug(UUID clientId, String slug);

    Page<Product> findAllByClientIdAndMarketplaceListedTrueAndActiveTrue(UUID clientId, Pageable pageable);

    Optional<Product> findByIdAndClientIdAndMarketplaceListedTrueAndActiveTrue(UUID id, UUID clientId);

    Optional<Product> findBySlugAndClientIdAndMarketplaceListedTrueAndActiveTrue(String slug, UUID clientId);

    long countByClientIdAndMarketplaceListedTrue(UUID clientId);

    /**
     * The buyer's own inventory row previously created from a given catalog
     * product. This is the primary match when incoming stock is applied - matching
     * on SKU is only the fallback, because a buyer may legitimately have renamed or
     * re-SKU'd their copy.
     */
    Optional<Product> findByClientIdAndSourceProductId(UUID clientId, UUID sourceProductId);

    /** "Pending delivery" section of the buyer's inventory: bought, paid for, not yet in hand. */
    List<Product> findAllByClientIdAndIncomingQuantityGreaterThan(UUID clientId, int quantity);

    /**
     * Row-locks the buyer's product while incoming quantity is adjusted. Payment
     * confirmation and receipt confirmation both read-modify-write
     * incoming_quantity, and a duplicated webhook must not be able to double it -
     * same rationale as findByIdAndClientIdForUpdate does for quantity_on_hand.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") UUID id);

    // ------------------------------------------------------------------------
    // The buyer-side vendor directory (M5).
    //
    // products.company_vendor_id and the product both belong to the same tenant
    // by construction, so these are ordinary in-tenant reads - no guard, no
    // filter lifting. The explicit clientId predicate is here for the usual
    // reason: it holds even if the Hibernate filter is ever left disabled.
    //
    // V11 deliberately added no finders for this column. These are the ones the
    // vendor detail screen and product<->vendor linking actually use, added at
    // the point of use rather than speculatively.
    // ------------------------------------------------------------------------

    /**
     * "What we buy from this supplier" - the products a company has filed under one
     * directory entry, alphabetical, which is the order the vendor detail screen
     * renders them in.
     */
    List<Product> findAllByClientIdAndCompanyVendorIdAndActiveTrueOrderByNameAsc(UUID clientId, UUID companyVendorId);

    /**
     * How many products would lose their supplier link if this vendor row went
     * away. Read before deactivating one, so the confirmation can say what the
     * consequence is instead of asking the buyer to guess.
     */
    long countByClientIdAndCompanyVendorIdAndActiveTrue(UUID clientId, UUID companyVendorId);

    /**
     * A seller's whole live catalogue, alphabetical - the population behind the
     * vendor stock-out report.
     *
     * <p>Inactive rows are excluded because a deactivated product is not a
     * stock-out, it is a withdrawn product, and reporting it would bury the ones the
     * seller can still do something about. Not paged: the caller has to compute
     * available-to-sell for every row (one batched commitment query over the whole
     * set) before it knows which rows qualify, so a page boundary applied before
     * that filter would be a page of the wrong things.
     */
    List<Product> findAllByClientIdAndActiveTrueOrderByNameAsc(UUID clientId);
}
