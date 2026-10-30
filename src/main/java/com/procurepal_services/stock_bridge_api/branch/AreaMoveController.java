package com.procurepal_services.stock_bridge_api.branch;

import com.procurepal_services.stock_bridge_api.branch.dto.AreaMoveRequest;
import com.procurepal_services.stock_bridge_api.branch.dto.AreaMoveResponse;
import com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Moving stock between the areas of a branch (STOCK_AREAS_PART_2.md). A move is a stock write: STOCK_OUT,
 * like sending stock (D-A8), and its refusals (too little in the area, an area closed) answer exactly as a
 * stock-out's do - see StockManagementExceptionHandler. Reading needs no permission beyond seeing the branch.
 */
@RestController
@RequiredArgsConstructor
public class AreaMoveController {

    private final AreaMoveService areaMoveService;

    @PostMapping("/api/area-moves")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('STOCK_OUT')")
    public AreaMoveResponse move(
            @Valid @RequestBody AreaMoveRequest request, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return areaMoveService.move(request, principal.getUserId());
    }

    @PostMapping("/api/area-moves/{id}/undo")
    @PreAuthorize("hasAuthority('STOCK_OUT')")
    public AreaMoveResponse undo(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return areaMoveService.undo(id, principal.getUserId());
    }

    @GetMapping("/api/area-moves/{id}")
    public AreaMoveResponse get(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return areaMoveService.get(id, principal.getUserId());
    }

    @GetMapping("/api/area-moves")
    public Page<AreaMoveResponse> list(
            @RequestParam(required = false) UUID branchId,
            @RequestParam(required = false) UUID productId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return areaMoveService.list(principal.getUserId(), branchId, productId, Math.max(page, 0), Math.clamp(size, 1, 100));
    }
}
