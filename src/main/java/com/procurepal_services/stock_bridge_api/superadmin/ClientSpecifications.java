package com.procurepal_services.stock_bridge_api.superadmin;

import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.ClientType;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/** Same pattern as ProductSpecifications, over clients rather than a tenant-scoped entity. */
final class ClientSpecifications {

    private ClientSpecifications() {
    }

    static Specification<Client> search(String search, Boolean active) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (search != null && !search.isBlank()) {
                String pattern = "%" + search.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), pattern), cb.like(cb.lower(root.get("slug")), pattern)));
            }

            if (active != null) {
                predicates.add(cb.equal(root.get("active"), active));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * The same search, narrowed to marketplace sellers.
     *
     * <h2>Why the type predicate is composed here rather than left to the caller</h2>
     * The vendor endpoints must never serve a COMPANY row under a vendor heading -
     * see {@code VendorNotFoundException} for the single-row form of the same rule.
     * Composing {@link #search} and adding {@code client_type = 'VENDOR'} keeps that
     * predicate in the query rather than in an if-statement each caller writes,
     * which is the same argument {@code ClientRepository.findByIdAndClientType}
     * makes. A caller that forgot it would get a working, plausible-looking list of
     * every tenant on the platform.
     *
     * <p>Note this deliberately excludes ProcurePal, which sells but is a COMPANY -
     * {@code ClientRepository.findAllByClientType} says the same at more length.
     * That is right for these endpoints: they manage third-party vendors, and
     * ProcurePal's own row is managed through /api/superadmin/clients like the
     * tenant it is.
     */
    static Specification<Client> vendors(String search, Boolean active) {
        return search(search, active)
                .and((root, query, cb) -> cb.equal(root.get("clientType"), ClientType.VENDOR));
    }
}
