package com.procurepal_services.stock_bridge_api.user;

import com.procurepal_services.stock_bridge_api.user.dto.RoleResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only role catalogue behind MANAGE_USERS: it exists to populate the role
 * picker on the users screen, so whoever can assign a role can list them, and
 * nobody else needs to.
 */
@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('MANAGE_USERS')")
public class RoleController {

    private final RoleCatalogService roleCatalogService;

    @GetMapping
    public List<RoleResponse> list() {
        return roleCatalogService.list();
    }
}
