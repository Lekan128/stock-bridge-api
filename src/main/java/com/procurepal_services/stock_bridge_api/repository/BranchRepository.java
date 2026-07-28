package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.Branch;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BranchRepository extends TenantScopedRepository<Branch, UUID> {

    /**
     * Every client is guaranteed to have exactly one of these - created with the
     * client by ClientSignupService, backfilled by V6 for older clients - so a
     * caller that needs "somewhere to hang this order" can rely on it existing
     * rather than defending against an empty Optional everywhere.
     */
    Optional<Branch> findByClientIdAndDefaultBranchTrue(UUID clientId);

    List<Branch> findAllByClientIdAndActiveTrueOrderByNameAsc(UUID clientId);

    long countByClientId(UUID clientId);

    long countByClientIdAndDefaultBranchTrue(UUID clientId);
}
