package com.procurepal_services.stock_bridge_api.founding;

import com.procurepal_services.stock_bridge_api.founding.dto.SetupRequestCounts;
import com.procurepal_services.stock_bridge_api.founding.dto.SetupRequestView;
import com.procurepal_services.stock_bridge_api.founding.dto.UpdateSetupRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The setup queue, for super admins (SecurityConfig requires the super-admin audience on
 * {@code /api/superadmin/**}; super admin is one flat role, so that is the only check).
 */
@RestController
@RequestMapping("/api/superadmin/setup-requests")
@RequiredArgsConstructor
public class SuperAdminSetupRequestController {

    private final SetupRequestQueueService queue;

    /** @param tab NEW, IN_PROGRESS, RUNNING or NOT_A_FIT; absent for the full history. */
    @GetMapping
    public Page<SetupRequestView> list(
            @RequestParam(required = false) String tab, @PageableDefault(size = 20) Pageable pageable) {
        return queue.list(tab, pageable);
    }

    @GetMapping("/counts")
    public SetupRequestCounts counts() {
        return queue.counts();
    }

    @PatchMapping("/{id}")
    public SetupRequestView update(@PathVariable UUID id, @Valid @RequestBody UpdateSetupRequest request) {
        return queue.update(id, request);
    }
}
