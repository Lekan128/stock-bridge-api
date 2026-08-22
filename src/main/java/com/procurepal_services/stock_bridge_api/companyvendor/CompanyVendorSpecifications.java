package com.procurepal_services.stock_bridge_api.companyvendor;

import com.procurepal_services.stock_bridge_api.entity.CompanyVendor;
import com.procurepal_services.stock_bridge_api.entity.CompanyVendorKind;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * The directory list: both kinds together, optionally narrowed to one, optionally
 * searched by name.
 *
 * <p>Builds the tenant-scoping predicate explicitly rather than relying solely on
 * TenantAwareEntity's Hibernate filter - same rationale as ProductSpecifications
 * and TenantScopedRepository. That matters more here than usual: a directory entry
 * is one company's private opinion about a supplier, and the failure mode of a
 * missing predicate is another company's notes and phone numbers, not a harmless
 * empty page.
 */
final class CompanyVendorSpecifications {

    private CompanyVendorSpecifications() {
    }

    /**
     * @param kind null to list both kinds together, which is the default the
     *     screen opens on: a buyer's supplier list is one list, and forcing them to
     *     pick a tab first would present an implementation detail as a choice.
     * @param search matched against name only. Not against notes: the notes column
     *     is private commentary ("chased us for payment twice"), and a name search
     *     that surfaced rows on the strength of it would show the buyer results
     *     they cannot see the reason for.
     */
    static Specification<CompanyVendor> forTenant(UUID clientId, CompanyVendorKind kind, String search) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("clientId"), clientId));
            // Deactivation is this feature's delete (see CompanyVendorService), so an
            // inactive row must never appear in the list - that is what makes the two
            // indistinguishable for every caller.
            predicates.add(cb.isTrue(root.get("active")));

            if (kind != null) {
                predicates.add(cb.equal(root.get("vendorKind"), kind));
            }

            if (search != null && !search.isBlank()) {
                String pattern = "%" + search.trim().toLowerCase() + "%";
                predicates.add(cb.like(cb.lower(root.get("name")), pattern));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
