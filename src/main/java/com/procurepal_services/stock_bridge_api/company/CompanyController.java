package com.procurepal_services.stock_bridge_api.company;

import com.procurepal_services.stock_bridge_api.company.dto.CompanyResponse;
import com.procurepal_services.stock_bridge_api.company.dto.UpdateCompanyRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own company. What /api/me is to a user, this is to the tenant
 * they belong to - and like /api/me it takes no id anywhere, because the only
 * company it can address is the one on the caller's token (see CompanyService
 * for why that matters more here than anywhere else in the app).
 *
 * <h2>Read is open, write is not</h2>
 * GET carries no @PreAuthorize, on the same structural reasoning ProfileController
 * gives: with no id in the path there is nothing to authorize access TO beyond
 * "are you signed in", which SecurityConfig has already answered by requiring the
 * tenant audience on /api/**. It would also be an odd line to draw - /api/me
 * already hands every authenticated user their company's name, identifier and
 * platform-owner flag, so gating those same three values here would only mean the
 * settings page renders for an OWNER and 403s for the storekeeper looking at it
 * read-only. The extra fields GET adds are the company's own phone number, its
 * own admin contact address and its payment terms: internal facts every colleague
 * either already knows or needs (the checkout screen decides whether to offer
 * pay-on-delivery from paymentTerms, and PLACE_ORDERS is a different permission
 * from this one).
 *
 * PUT is gated on MANAGE_COMPANY_PROFILE, which V7 grants to OWNER alone - see
 * that migration for why the other four roles were considered and left out.
 */
@RestController
@RequestMapping("/api/company")
@RequiredArgsConstructor
public class CompanyController {

    private final CompanyService companyService;

    @GetMapping
    public CompanyResponse get() {
        return companyService.get();
    }

    @PutMapping
    @PreAuthorize("hasAuthority('MANAGE_COMPANY_PROFILE')")
    public CompanyResponse update(@Valid @RequestBody UpdateCompanyRequest request) {
        return companyService.update(request);
    }
}
