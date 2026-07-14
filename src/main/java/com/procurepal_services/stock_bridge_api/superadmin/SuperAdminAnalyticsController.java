package com.procurepal_services.stock_bridge_api.superadmin;

import com.procurepal_services.stock_bridge_api.superadmin.dto.PlatformAggregateResponse;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Platform-wide (cross-tenant) reporting for super admins - see SuperAdminAggregateService. */
@RestController
@RequestMapping("/api/superadmin/analytics")
@RequiredArgsConstructor
public class SuperAdminAnalyticsController {

    private final SuperAdminAggregateService superAdminAggregateService;

    @GetMapping("/aggregate")
    public PlatformAggregateResponse aggregate(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return superAdminAggregateService.aggregate(from, to);
    }
}
