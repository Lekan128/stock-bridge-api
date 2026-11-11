package com.procurepal_services.stock_bridge_api.branch;

import com.procurepal_services.stock_bridge_api.branch.dto.AreaHealthResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/superadmin/areas/health} - STOCK_AREAS_BUILD_PLAN.md section 6 across every company.
 * Super admins only, by path (SecurityConfig), like Branch Health.
 */
@RestController
@RequiredArgsConstructor
public class AreaHealthController {

    private final AreaHealthService areaHealthService;

    @GetMapping("/api/superadmin/areas/health")
    public AreaHealthResponse health() {
        return areaHealthService.health();
    }
}
