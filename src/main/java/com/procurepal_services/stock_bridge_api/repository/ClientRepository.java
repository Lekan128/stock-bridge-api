package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.Client;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ClientRepository extends JpaRepository<Client, UUID>, JpaSpecificationExecutor<Client> {

    Optional<Client> findBySlug(String slug);

    long countByActiveTrue();

    /**
     * ProcurePal. At most one row can match (partial unique index on
     * is_platform_owner), so the Optional is "has the platform owner been seeded
     * yet", not "which one".
     *
     * Prefer PlatformOwnerGuard over calling this directly for authorization -
     * the guard is where the 403 behaviour is defined. This finder is for the
     * public catalog, which needs the platform owner's client_id as a filter and
     * must degrade to an empty catalog (not an error) if no platform owner exists.
     */
    Optional<Client> findByPlatformOwnerTrue();

    long countByPlatformOwnerTrue();
}
