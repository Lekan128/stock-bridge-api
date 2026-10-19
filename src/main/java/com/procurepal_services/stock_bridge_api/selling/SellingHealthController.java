package com.procurepal_services.stock_bridge_api.selling;

import com.procurepal_services.stock_bridge_api.selling.dto.SellingHealthResponse;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/superadmin/selling/health} - SELLING_PRICE_PLAN.md section 9 across every company,
 * the last 14 days by default. Super admins only, by path (SecurityConfig).
 */
@RestController
@RequiredArgsConstructor
public class SellingHealthController {

    private final SellingHealthService sellingHealthService;

    @GetMapping("/api/superadmin/selling/health")
    public SellingHealthResponse health(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return sellingHealthService.health(from, to);
    }
}
