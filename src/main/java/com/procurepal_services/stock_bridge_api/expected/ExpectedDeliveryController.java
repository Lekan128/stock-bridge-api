package com.procurepal_services.stock_bridge_api.expected;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.entity.ExpectedDeliveryStatus;
import com.procurepal_services.stock_bridge_api.expected.dto.ExpectedDeliveryRequest;
import com.procurepal_services.stock_bridge_api.expected.dto.ExpectedDeliveryResponse;
import com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/expected-deliveries} - what a company is waiting for from an off-platform supplier
 * (BULK_IMPORT_CX_PLAN.md task 3.1).
 *
 * <p>Writing one needs MANAGE_INVENTORY, the same authority recording a delivery needs: saying
 * "100 bags are coming from Tony" is part of the same job as saying "they arrived", and a
 * storekeeper who can do the second must be able to do the first. Reading is open to anyone who
 * can see products, since "what have we got coming?" is a question about the catalog.
 */
@RestController
@RequestMapping("/api/expected-deliveries")
@RequiredArgsConstructor
public class ExpectedDeliveryController {

    private final ExpectedDeliveryService expectedDeliveryService;

    /**
     * @param status defaults to nothing, meaning all of them. The screen asks for OPEN.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_PRODUCTS')")
    public Page<ExpectedDeliveryResponse> list(
            @RequestParam(required = false) ExpectedDeliveryStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return expectedDeliveryService.list(status, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_PRODUCTS')")
    public ExpectedDeliveryResponse get(@PathVariable UUID id) {
        return expectedDeliveryService.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
    public ResponseEntity<ExpectedDeliveryResponse> create(
            @Valid @RequestBody ExpectedDeliveryRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(expectedDeliveryService.create(request, principal.getUserId()));
    }

    /**
     * Called off. A POST rather than a DELETE because the record stays - what you expected and
     * never got is worth keeping, and a supplier's history of not delivering is exactly the sort
     * of thing a buyer wants to be able to look back at.
     */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
    public ExpectedDeliveryResponse cancel(@PathVariable UUID id) {
        return expectedDeliveryService.cancel(id);
    }

    @ExceptionHandler(ExpectedDeliveryException.class)
    public ResponseEntity<ApiError> handle(ExpectedDeliveryException exception) {
        return ResponseEntity.status(exception.status()).body(new ApiError(exception.getMessage()));
    }
}
