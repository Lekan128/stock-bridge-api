package com.procurepal_services.stock_bridge_api.branch;

import com.procurepal_services.stock_bridge_api.branch.dto.AreaRequest;
import com.procurepal_services.stock_bridge_api.branch.dto.AreaResponse;
import com.procurepal_services.stock_bridge_api.branch.dto.AreaUpdateRequest;
import com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A branch's areas (STOCK_AREAS_PART_1.md). Read with the branches ({@code GET /api/branches} lists each
 * branch's areas). Changing them needs {@code MANAGE_BRANCHES}, like the branches themselves, and working
 * at that branch - checked fresh by {@link AreaService}.
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('MANAGE_BRANCHES')")
public class AreaController {

    private final AreaService areaService;

    @PostMapping("/api/branches/{branchId}/areas")
    @ResponseStatus(HttpStatus.CREATED)
    public List<AreaResponse> add(
            @PathVariable UUID branchId,
            @Valid @RequestBody AreaRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return areaService.add(branchId, request, principal.getUserId());
    }

    @PatchMapping("/api/areas/{id}")
    public AreaResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody AreaUpdateRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return areaService.update(id, request, principal.getUserId());
    }

    @PostMapping("/api/areas/{id}/close")
    public AreaResponse close(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return areaService.close(id, principal.getUserId());
    }

    @PostMapping("/api/areas/{id}/reopen")
    public AreaResponse reopen(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return areaService.reopen(id, principal.getUserId());
    }
}
