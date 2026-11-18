package com.procurepal_services.stock_bridge_api.selling;

import com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Close the day (V97, selling till Step 13): a drawer's day, for whoever sells there. Online only. */
@RestController
@RequestMapping("/api/cash-days")
@RequiredArgsConstructor
public class CashDayController {

    private final CashDayService cashDayService;

    public record StartRequest(UUID branchId, UUID areaId, BigDecimal floatAmount, Boolean skipped) {}

    public record MoneyOutRequest(BigDecimal amount, CashDayService.OutKind kind, String note) {}

    public record CountRequest(BigDecimal counted) {}

    @GetMapping("/till")
    @PreAuthorize("hasAuthority('STOCK_OUT')")
    public CashDayService.TillState till(
            @RequestParam UUID branchId, @RequestParam(required = false) UUID areaId,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return cashDayService.till(principal.getClientId(), branchId, areaId, principal.getUserId());
    }

    @GetMapping("/today")
    @PreAuthorize("hasAuthority('STOCK_OUT')")
    public List<CashDayService.Drawer> today(
            @RequestParam UUID branchId, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return cashDayService.today(principal.getClientId(), branchId, principal.getUserId());
    }

    @GetMapping("/left-open")
    @PreAuthorize("hasAuthority('VIEW_ANALYTICS')")
    public List<CashDayService.Drawer> leftOpen(
            @RequestParam List<UUID> branchIds, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return cashDayService.leftOpen(principal.getClientId(), branchIds, principal.getUserId());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('STOCK_OUT')")
    public CashDayService.Drawer start(
            @RequestBody StartRequest request, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return cashDayService.start(principal.getClientId(), request.branchId(), request.areaId(), request.floatAmount(),
                Boolean.TRUE.equals(request.skipped()), principal.getUserId());
    }

    @PostMapping("/{id}/money-out")
    @PreAuthorize("hasAuthority('STOCK_OUT')")
    public void moneyOut(
            @PathVariable UUID id, @RequestBody MoneyOutRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        cashDayService.moneyOut(principal.getClientId(), id, request.amount(), request.kind(), request.note(), principal.getUserId());
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('STOCK_OUT')")
    public CashDayService.CloseResult close(
            @PathVariable UUID id, @RequestBody CountRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return cashDayService.close(principal.getClientId(), id, request.counted(), principal.getUserId());
    }

    @PostMapping("/{id}/count-again")
    @PreAuthorize("hasAuthority('STOCK_OUT')")
    public CashDayService.CloseResult countAgain(
            @PathVariable UUID id, @RequestBody CountRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return cashDayService.countAgain(principal.getClientId(), id, request.counted(), principal.getUserId());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('STOCK_OUT')")
    public CashDayService.CloseResult get(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return cashDayService.result(principal.getClientId(), id, principal.getUserId());
    }
}
