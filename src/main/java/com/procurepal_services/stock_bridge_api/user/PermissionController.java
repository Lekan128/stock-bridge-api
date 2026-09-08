package com.procurepal_services.stock_bridge_api.user;

import com.procurepal_services.stock_bridge_api.repository.PermissionRepository;
import com.procurepal_services.stock_bridge_api.user.dto.PermissionResponse;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The full privilege catalogue - every code that exists, not just the ones some role already
 * holds - so the Roles & Privileges matrix can offer every checkbox, including ones no role has
 * been given yet. Gated on MANAGE_ROLES alone: unlike GET /api/roles, nothing outside that screen
 * needs this list.
 */
@RestController
@RequestMapping("/api/permissions")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('MANAGE_ROLES')")
public class PermissionController {

    private final PermissionRepository permissionRepository;

    @GetMapping
    public List<PermissionResponse> list() {
        return permissionRepository.findAll().stream()
                .sorted(Comparator.comparing(p -> p.getCode()))
                .map(PermissionResponse::from)
                .toList();
    }
}
