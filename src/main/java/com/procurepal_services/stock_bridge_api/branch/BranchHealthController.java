package com.procurepal_services.stock_bridge_api.branch;

import com.procurepal_services.stock_bridge_api.branch.dto.BranchHealthResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/superadmin/branches/health} - MULTI_BRANCH_BUILD_PLAN.md section 3.3 across every
 * company. Super admins only, by path (SecurityConfig).
 */
@RestController
@RequiredArgsConstructor
public class BranchHealthController {

    private final BranchHealthService branchHealthService;

    @GetMapping("/api/superadmin/branches/health")
    public BranchHealthResponse health() {
        return branchHealthService.health();
    }
}
