package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.tenant.TenantAwareEntity;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

/**
 * Base for repositories over tenant-scoped entities. findAllByClientId /
 * findByIdAndClientId take client_id explicitly, so isolation holds by
 * construction even if TenantAwareEntity's Hibernate filter is ever left
 * disabled - see TenantResolutionFilter for that first layer. The
 * ...ForCurrentTenant() methods derive client_id from TenantContext instead
 * of accepting it as a parameter, so callers can't accidentally (or via a
 * bug) pass the wrong tenant id - they're the ones repository/service code
 * should use day to day.
 */
@NoRepositoryBean
public interface TenantScopedRepository<T extends TenantAwareEntity, ID> extends JpaRepository<T, ID> {

    List<T> findAllByClientId(UUID clientId);

    Page<T> findAllByClientId(UUID clientId, Pageable pageable);

    Optional<T> findByIdAndClientId(ID id, UUID clientId);

    default List<T> findAllForCurrentTenant() {
        return findAllByClientId(requireCurrentTenantId());
    }

    default Page<T> findAllForCurrentTenant(Pageable pageable) {
        return findAllByClientId(requireCurrentTenantId(), pageable);
    }

    default Optional<T> findByIdForCurrentTenant(ID id) {
        return findByIdAndClientId(id, requireCurrentTenantId());
    }

    private static UUID requireCurrentTenantId() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context set; cannot execute a tenant-scoped query");
        }
        return tenantId;
    }
}
