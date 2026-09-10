package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.Role;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, UUID> {

    Optional<Role> findByName(String name);

    /** Every system role, plus this tenant's own custom roles - see Role's javadoc. */
    List<Role> findByClientIdIsNullOrClientId(UUID clientId);

    /**
     * Scopes a lookup to a role the tenant actually owns. A system role's clientId is always
     * NULL, so this can never match one - which is what keeps a custom-role edit/delete from
     * ever reaching a system role without a separate isSystem branch.
     */
    Optional<Role> findByIdAndClientId(UUID id, UUID clientId);

    boolean existsByClientIdAndNameIgnoreCase(UUID clientId, String name);
}
