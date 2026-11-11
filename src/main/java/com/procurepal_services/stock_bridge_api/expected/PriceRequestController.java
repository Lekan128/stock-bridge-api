package com.procurepal_services.stock_bridge_api.expected;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/price-requests} - asking several suppliers for prices (PURCHASE_ORDER_PLAN.md section 11.3). */
@RestController
@RequestMapping("/api/price-requests")
@RequiredArgsConstructor
public class PriceRequestController {

    private final PriceRequestService service;

    public record CreateBody(List<PriceRequestService.NewLine> lines, List<UUID> vendorIds, LocalDate neededBy,
            LocalDate replyBy, UUID branchId, @Size(max = 500) String note) {
    }

    /** {@code kind} PICKED with {@code picks} (section 12.2); CHEAPEST or SUPPLIER from an older screen. */
    public record ChooseBody(String kind, UUID supplierId, List<PriceRequestService.Pick> picks) {
    }

    public record ReplyByBody(LocalDate replyBy) {
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('CREATE_PURCHASE_ORDER', 'SEND_PURCHASE_ORDER')")
    public ResponseEntity<PriceRequestService.Request> create(
            @RequestBody CreateBody body, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(new PriceRequestService.NewRequest(
                body.lines(), body.vendorIds(), body.neededBy(), body.replyBy(), body.branchId(), body.note()),
                principal.getUserId()));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_PRODUCTS')")
    public List<PriceRequestService.Row> list(
            @RequestParam(defaultValue = "OPEN") String status, @RequestParam(defaultValue = "20") int size) {
        return service.list(status, size);
    }

    @GetMapping("/counts")
    @PreAuthorize("hasAuthority('VIEW_PRODUCTS')")
    public Map<String, Long> counts() {
        return Map.of("open", service.openCount());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_PRODUCTS')")
    public PriceRequestService.Request get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/{id}/suppliers/{supplierId}/shared")
    @PreAuthorize("hasAnyAuthority('CREATE_PURCHASE_ORDER', 'SEND_PURCHASE_ORDER')")
    public PriceRequestService.Request shared(
            @PathVariable UUID id, @PathVariable UUID supplierId, @RequestParam String channel) {
        return service.markShared(id, supplierId, channel);
    }

    /** "Add Tony's prices": what a supplier said on the phone, typed by the buyer. */
    @PutMapping("/{id}/suppliers/{supplierId}/reply")
    @PreAuthorize("hasAnyAuthority('CREATE_PURCHASE_ORDER', 'SEND_PURCHASE_ORDER')")
    public PriceRequestService.Request reply(
            @PathVariable UUID id, @PathVariable UUID supplierId, @RequestBody PriceRequestService.Reply reply,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return service.typeReply(id, supplierId, reply, principal.getUserId());
    }

    /** "Make 2 purchase orders": CHEAPEST for each item, or everything from one SUPPLIER. */
    @PostMapping("/{id}/choose")
    @PreAuthorize("hasAnyAuthority('CREATE_PURCHASE_ORDER', 'SEND_PURCHASE_ORDER')")
    public PriceRequestService.ChooseResult choose(
            @PathVariable UUID id, @RequestBody ChooseBody body,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return service.choose(id, body.kind(), body.supplierId(), body.picks(), principal.getUserId());
    }

    /** "Give more time": a new Reply by, while nothing is decided (section 12.1). */
    @PostMapping("/{id}/reply-by")
    @PreAuthorize("hasAnyAuthority('CREATE_PURCHASE_ORDER', 'SEND_PURCHASE_ORDER')")
    public PriceRequestService.Request giveMoreTime(
            @PathVariable UUID id, @RequestBody ReplyByBody body,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return service.giveMoreTime(id, body.replyBy(), principal.getUserId());
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAnyAuthority('CREATE_PURCHASE_ORDER', 'SEND_PURCHASE_ORDER')")
    public PriceRequestService.Request close(
            @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return service.close(id, principal.getUserId());
    }

    @ExceptionHandler(ExpectedDeliveryException.class)
    public ResponseEntity<ApiError> handle(ExpectedDeliveryException exception) {
        return ResponseEntity.status(exception.status()).body(new ApiError(exception.getMessage()));
    }
}
