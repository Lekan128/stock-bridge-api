package com.procurepal_services.stock_bridge_api.selling;

import com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A branch's transfer accounts (V96, selling till Step 12). Everyone reads the switched-on ones with the branch list;
 * this is where whoever manages branches sees all of them and adds or switches them off.
 */
@RestController
@RequiredArgsConstructor
public class PaymentAccountController {

    private final PaymentAccountService paymentAccountService;

    @GetMapping("/api/branches/{branchId}/payment-accounts")
    @PreAuthorize("hasAuthority('MANAGE_BRANCHES')")
    public List<PaymentAccountService.PaymentAccount> list(
            @PathVariable UUID branchId, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return paymentAccountService.byBranch(principal.getClientId(), true).getOrDefault(branchId, List.of());
    }

    @PostMapping("/api/branches/{branchId}/payment-accounts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('MANAGE_BRANCHES')")
    public PaymentAccountService.PaymentAccount add(
            @PathVariable UUID branchId,
            @RequestBody PaymentAccountService.AccountRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return paymentAccountService.add(principal.getClientId(), branchId, request);
    }

    @PatchMapping("/api/payment-accounts/{id}")
    @PreAuthorize("hasAuthority('MANAGE_BRANCHES')")
    public PaymentAccountService.PaymentAccount update(
            @PathVariable UUID id,
            @RequestBody PaymentAccountService.AccountRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return paymentAccountService.update(principal.getClientId(), id, request);
    }
}
