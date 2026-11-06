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
    private final PurchaseOrderPdf purchaseOrderPdf;
    private final LogoFetcher logoFetcher;
    private final PurchaseOrderEmailService purchaseOrderEmailService;
    private final PurchaseOrderPicker purchaseOrderPicker;

    /**
     * @param status defaults to nothing, meaning all of them. The screen asks for OPEN.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_PRODUCTS')")
    public Page<ExpectedDeliveryResponse> list(
            @RequestParam(required = false) ExpectedDeliveryStatus status,
            // Multiple branches: going to this branch; absent is every branch the person sees.
            @RequestParam(required = false) UUID branchId,
            // The Purchase orders list (redesign 2026-11-04): a filter chip, what was typed, a supplier.
            @RequestParam(required = false) ExpectedDeliveryService.View view,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID vendorId,
            @PageableDefault(size = 20) Pageable pageable,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
                    com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal principal) {
        UUID userId = principal == null ? null : principal.getUserId();
        if (view != null || (search != null && !search.isBlank()) || vendorId != null) {
            return expectedDeliveryService.search(view, search, vendorId, pageable, userId, branchId);
        }
        return expectedDeliveryService.list(status, pageable, userId, branchId);
    }

    /** How many each of the list's filter chips holds, with the same search, supplier and branch. */
    @GetMapping("/counts")
    @PreAuthorize("hasAuthority('VIEW_PRODUCTS')")
    public ExpectedDeliveryService.Counts counts(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID vendorId,
            @RequestParam(required = false) UUID branchId,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return expectedDeliveryService.counts(search, vendorId, principal == null ? null : principal.getUserId(), branchId);
    }

    /**
     * The new purchase order's product search, with what decides "how many?" on each line: stock
     * left at the branch, low or out, what is already coming, and last time's quantity from this
     * supplier. {@code runningLow} lists the supplier's products that are low or out.
     */
    @GetMapping("/picker")
    @PreAuthorize("hasAnyAuthority('MANAGE_INVENTORY', 'CREATE_PURCHASE_ORDER', 'SEND_PURCHASE_ORDER')")
    public java.util.List<PurchaseOrderPicker.Line> picker(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID vendorId,
            @RequestParam(required = false, defaultValue = "false") boolean runningLow,
            @RequestParam(required = false) UUID branchId,
            @RequestParam(required = false, defaultValue = "8") int limit,
            // "Order more" / "Order again": exactly these products, comma-separated.
            @RequestParam(required = false) java.util.List<UUID> productIds,
            // Changing an order: its own lines aren't "coming" to itself.
            @RequestParam(required = false) UUID excludeOrderId) {
        return purchaseOrderPicker.lines(search, vendorId, runningLow, branchId, limit,
                productIds == null ? java.util.List.of() : productIds, excludeOrderId);
    }

    /** Suppliers ordered from, newest first, with a few of what was asked for - the supplier search's order. */
    @GetMapping("/recent-suppliers")
    @PreAuthorize("hasAnyAuthority('MANAGE_INVENTORY', 'CREATE_PURCHASE_ORDER', 'SEND_PURCHASE_ORDER')")
    public java.util.List<PurchaseOrderPicker.RecentSupplier> recentSuppliers() {
        return purchaseOrderPicker.recentSuppliers();
    }

    /**
     * Change a purchase order: its lines, needed-by and note. Once the supplier has it, the change is
     * the next revision and the order is back on To send until they are given it.
     */
    @org.springframework.web.bind.annotation.PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('CREATE_PURCHASE_ORDER', 'SEND_PURCHASE_ORDER')")
    public ExpectedDeliveryResponse change(
            @PathVariable UUID id,
            @Valid @RequestBody ExpectedDeliveryRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return expectedDeliveryService.change(id, request, principal == null ? null : principal.getUserId());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_PRODUCTS')")
    public ExpectedDeliveryResponse get(
            @PathVariable UUID id,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
                    com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal principal) {
        return expectedDeliveryService.get(id, principal == null ? null : principal.getUserId());
    }

    /**
     * The purchase order as a PDF, drawn now from what was frozen when it was made - never stored.
     * {@code inline}, so the browser opens its own viewer (print / download in its toolbar).
     */
    @GetMapping("/{id}/pdf")
    @PreAuthorize("hasAuthority('VIEW_PRODUCTS')")
    public ResponseEntity<byte[]> pdf(
            @PathVariable UUID id,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
                    com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal principal) {
        PurchaseOrderSnapshot order = expectedDeliveryService.snapshotOf(id, principal == null ? null : principal.getUserId());
        byte[] pdf = purchaseOrderPdf.render(order, logoFetcher.fetch(order.buyer().logoUrl()));
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.inline()
                                .filename(order.poNumber() + " " + order.supplier().name() + ".pdf",
                                        java.nio.charset.StandardCharsets.UTF_8)
                                .build().toString())
                .body(pdf);
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('MANAGE_INVENTORY', 'CREATE_PURCHASE_ORDER', 'SEND_PURCHASE_ORDER')")
    public ResponseEntity<ExpectedDeliveryResponse> create(
            @Valid @RequestBody ExpectedDeliveryRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        ExpectedDeliveryResponse created = expectedDeliveryService.create(request, principal.getUserId());
        if (request.isPurchaseOrder() && request.isEmailRequested() && created.approval() == null) {
            // The order is already saved. Whether the email went is written on it either way.
            purchaseOrderEmailService.send(created.id(), principal.getUserId());
            created = expectedDeliveryService.get(created.id(), principal.getUserId());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * "The buyer opened WhatsApp" or "copied the message" - written on the order so the line still
     * reads true a week later. All we can know; never "delivered" or "read".
     */
    @PostMapping("/{id}/shared")
    @PreAuthorize("hasAuthority('SEND_PURCHASE_ORDER')")
    public ExpectedDeliveryResponse shared(
            @PathVariable UUID id,
            @org.springframework.web.bind.annotation.RequestParam ExpectedDeliveryService.ShareChannel channel,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return expectedDeliveryService.markShared(id, principal.getUserId(), channel);
    }

    /**
     * Emails a purchase order to its supplier - the "Send email" on the order, and the resend. One
     * press, one email. Answers with the order so the screen can read the outcome off it.
     */
    @PostMapping("/{id}/email")
    @PreAuthorize("hasAuthority('SEND_PURCHASE_ORDER')")
    public ExpectedDeliveryResponse email(
            @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        purchaseOrderEmailService.send(id, principal.getUserId());
        return expectedDeliveryService.get(id, principal.getUserId());
    }

    /** "Approve and send" (plan section 11.5): approved; the screen then offers the ways to send it. */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('SEND_PURCHASE_ORDER')")
    public ExpectedDeliveryResponse approve(
            @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return expectedDeliveryService.approve(id, principal.getUserId());
    }

    /** "Ask for a change": back to whoever made it, with a note saying what. */
    public record AskForChangeRequest(@jakarta.validation.constraints.Size(max = 500) String note) {
    }

    @PostMapping("/{id}/ask-change")
    @PreAuthorize("hasAuthority('SEND_PURCHASE_ORDER')")
    public ExpectedDeliveryResponse askForChange(
            @PathVariable UUID id,
            @Valid @RequestBody AskForChangeRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return expectedDeliveryService.askForChange(id, principal.getUserId(), request.note());
    }

    /**
     * Called off. A POST rather than a DELETE because the record stays - what you expected and
     * never got is worth keeping, and a supplier's history of not delivering is exactly the sort
     * of thing a buyer wants to be able to look back at.
     */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyAuthority('MANAGE_INVENTORY', 'SEND_PURCHASE_ORDER', 'CREATE_PURCHASE_ORDER')")
    public ExpectedDeliveryResponse cancel(
            @PathVariable UUID id,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
                    com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal principal) {
        return expectedDeliveryService.cancel(id, principal == null ? null : principal.getUserId());
    }

    @ExceptionHandler(ExpectedDeliveryException.class)
    public ResponseEntity<ApiError> handle(ExpectedDeliveryException exception) {
        return ResponseEntity.status(exception.status()).body(new ApiError(exception.getMessage()));
    }
}
