package com.procurepal_services.stock_bridge_api.order;

import com.procurepal_services.stock_bridge_api.order.dto.CheckoutQuoteRequest;
import com.procurepal_services.stock_bridge_api.order.dto.CheckoutQuoteResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the current cart would cost, and everything that would stop it becoming an
 * order. Split from OrderController because it is idempotent and safe to call on
 * every keystroke of the address picker, while POST /api/orders is neither.
 *
 * PLACE_ORDERS rather than BROWSE_MARKETPLACE: the quote reveals the company's
 * payment terms and pay-on-delivery limit, which is commercial information about the
 * relationship, not catalog information.
 */
@RestController
@RequestMapping("/api/checkout")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('PLACE_ORDERS')")
public class CheckoutController {

    private final CheckoutService checkoutService;

    @PostMapping("/quote")
    public CheckoutQuoteResponse quote(@Valid @RequestBody(required = false) CheckoutQuoteRequest request) {
        return checkoutService.quote(request);
    }
}
