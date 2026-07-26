package com.procurepal_services.stock_bridge_api.user;

import com.procurepal_services.stock_bridge_api.repository.RoleRepository;
import com.procurepal_services.stock_bridge_api.user.dto.RoleResponse;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serves the assignable roles from the roles table rather than a compiled-in
 * list. Roles are global (no client_id - see the roles table in V1), so there
 * is nothing tenant-scoped to isolate here; the point of reading them from the
 * database is that the product intends to grow tenant-defined roles, and a
 * frontend that renders whatever the API returns needs no change when that
 * lands.
 */
@Service
@RequiredArgsConstructor
public class RoleCatalogService {

    private final RoleRepository roleRepository;

    /** Sorted by name so the options render in a stable order across calls. */
    @Transactional(readOnly = true)
    public List<RoleResponse> list() {
        return roleRepository.findAll().stream()
                .sorted(Comparator.comparing(role -> role.getName()))
                .map(RoleResponse::from)
                .toList();
    }
}
