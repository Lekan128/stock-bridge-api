package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.User;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends TenantScopedRepository<User, UUID> {

    Optional<User> findByClientIdAndUsername(UUID clientId, String username);

    long countByClientIdAndRole_NameAndActiveTrue(UUID clientId, String roleName);

    long countByClientId(UUID clientId);

    long countByClientIdAndActiveTrue(UUID clientId);
}
