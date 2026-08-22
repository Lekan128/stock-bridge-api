package com.procurepal_services.stock_bridge_api.companyvendor;

import com.procurepal_services.stock_bridge_api.entity.CompanyVendor;
import com.procurepal_services.stock_bridge_api.repository.CompanyVendorRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Give me this directory entry, if it is really mine" - the single resolution
 * step every surface that takes a vendor id from a request must go through.
 *
 * <h2>Why this is a component and not a private method</h2>
 * Three callers need it and they are in two packages: the vendor screens, the
 * purchase-history endpoint, and product editing, where a buyer picks a supplier
 * for one of their products. The check is the same each time - active, and
 * belonging to the caller's tenant - and a vendor id arriving in a request body is
 * precisely where a copied-and-slightly-wrong version of it would leak another
 * company's supplier into a product row. One implementation, one place to be
 * right.
 *
 * <p>{@code findByIdAndClientIdAndActiveTrue} carries the client_id explicitly, so
 * this holds even with TenantAwareEntity's Hibernate filter disabled - the same
 * belt-and-braces rule the rest of the tenant-scoped code follows.
 */
@Component
@RequiredArgsConstructor
public class CompanyVendorLookup {

    private final CompanyVendorRepository companyVendorRepository;

    /** 404 rather than 403 for another company's id - see CompanyVendorNotFoundException. */
    @Transactional(readOnly = true)
    public CompanyVendor require(UUID id) {
        return find(id).orElseThrow(CompanyVendorNotFoundException::new);
    }

    /** For callers that want to turn "not found" into their own error - e.g. a field error on a form. */
    @Transactional(readOnly = true)
    public Optional<CompanyVendor> find(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return companyVendorRepository.findByIdAndClientIdAndActiveTrue(id, requireTenantId());
    }

    private static UUID requireTenantId() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context set");
        }
        return tenantId;
    }
}
