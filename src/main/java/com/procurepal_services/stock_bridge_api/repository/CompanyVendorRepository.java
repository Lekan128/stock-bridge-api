package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.CompanyVendor;
import com.procurepal_services.stock_bridge_api.entity.CompanyVendorKind;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * A buying company's own supplier directory. CompanyVendor is tenant-scoped, so
 * everything here is safe for that company by default: the client_id-carrying
 * finders hold even with the Hibernate tenant filter off, which is the same
 * belt-and-braces rule OrderRepository and ProductRepository follow.
 *
 * <p>There is no cross-tenant read here and there should never be one. Unlike
 * orders - where the seller legitimately needs to see the buyer's row and goes
 * through PlatformOwnerGuard.readAcrossTenants to do it - a directory entry is
 * one company's private bookkeeping about a supplier. A vendor has no business
 * knowing which companies list them, what those companies noted about them, or
 * what they last paid. If a "your customers" view is ever wanted for vendors, it
 * must be built from orders, which record a real transaction, not from these
 * rows, which record an opinion.
 */
public interface CompanyVendorRepository
        extends TenantScopedRepository<CompanyVendor, UUID>, JpaSpecificationExecutor<CompanyVendor> {

    /** The directory list, alphabetical - the order the vendors screen renders. */
    List<CompanyVendor> findAllByClientIdAndActiveTrueOrderByNameAsc(UUID clientId);

    Page<CompanyVendor> findAllByClientIdAndActiveTrueOrderByNameAsc(UUID clientId, Pageable pageable);

    List<CompanyVendor> findAllByClientIdAndVendorKindAndActiveTrueOrderByNameAsc(
            UUID clientId, CompanyVendorKind vendorKind);

    Optional<CompanyVendor> findByIdAndClientIdAndActiveTrue(UUID id, UUID clientId);

    /**
     * The find-or-create the auto-linking on purchase runs on every order.
     *
     * <p>Deliberately ignores {@code active}: a company that deactivated a vendor
     * and then buys from them again must get their existing row back and reactivate
     * it, not a second one. The partial unique index on
     * (client_id, platform_client_id) would reject the duplicate anyway - this is
     * what stops the insert being attempted in the first place.
     */
    Optional<CompanyVendor> findByClientIdAndPlatformClientId(UUID clientId, UUID platformClientId);

    boolean existsByClientIdAndPlatformClientId(UUID clientId, UUID platformClientId);

    long countByClientIdAndActiveTrue(UUID clientId);

    long countByClientIdAndVendorKindAndActiveTrue(UUID clientId, CompanyVendorKind vendorKind);
}
