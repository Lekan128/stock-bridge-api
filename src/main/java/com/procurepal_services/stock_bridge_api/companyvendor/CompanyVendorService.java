package com.procurepal_services.stock_bridge_api.companyvendor;

import com.procurepal_services.stock_bridge_api.address.NigerianStates;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorDetailResponse;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorResponse;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.PlatformVendorSummary;
import com.procurepal_services.stock_bridge_api.entity.CompanyVendor;
import com.procurepal_services.stock_bridge_api.entity.CompanyVendorKind;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.CompanyVendorRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A buying company's own list of who it buys from (VENDOR_RESEARCH.md Section C
 * item 12): the marketplace sellers it has really traded with, and the local
 * suppliers who will never register on ProcurePaddy, in one list.
 *
 * <h2>Isolation</h2>
 * Twice over, like every tenant-scoped service here: TenantAwareEntity's Hibernate
 * filter, plus an explicit {@code client_id} predicate on every finder and in
 * {@link CompanyVendorSpecifications}. The stakes are higher than usual because a
 * directory row carries what one company privately noted about a supplier and what
 * it privately paid them - a missing predicate leaks commercial intelligence, not
 * an anonymous list of names.
 *
 * <h2>Two kinds, one of which this service does not create</h2>
 * Only EXTERNAL rows are created and edited here, and {@link CompanyVendorRequest}
 * cannot express anything else. VERIFIED rows are written in exactly one place, on
 * a real purchase - {@link CompanyVendorLinkService}. That split is what makes
 * "you traded with them" a fact the platform asserts rather than a claim a buyer
 * can type in.
 *
 * <h2>Delete is a deactivation, unconditionally</h2>
 * Not "when the vendor has history" - always, for the same reason
 * DeliveryAddressService gives: a rule with an exception is a rule someone will
 * get wrong later, and the check would have to be re-derived at every call site.
 * Three concrete things a hard delete would destroy, only the first of which is
 * obvious:
 *
 * <ul>
 *   <li>{@code products.company_vendor_id} is ON DELETE SET NULL, so removing a
 *       vendor silently unlinks every product bought from them - and the buyer
 *       finds out months later when a product's supplier field is blank and
 *       nothing says why.</li>
 *   <li>A VERIFIED row records a real trade. Deleting it would let a company erase
 *       the platform's record of who it bought from, which is not the company's
 *       call to make.</li>
 *   <li>Purchase history and last purchase price are derived from orders, which
 *       survive - so a deleted vendor would leave orphaned history that no screen
 *       can reach.</li>
 * </ul>
 *
 * <p>DELETE answers 204 and the row leaves the list, which is indistinguishable
 * from a removal for every caller. A deactivated VERIFIED row comes back by itself
 * if the company buys from that seller again - see
 * {@link CompanyVendorRepository#findByClientIdAndPlatformClientId}, which ignores
 * {@code active} for precisely that reason.
 *
 * <h2>Deactivating a VERIFIED row IS allowed, editing one is not</h2>
 * They are different claims. "Stop showing me this supplier" is the company's own
 * opinion about its own directory; "this supplier is called something else" is a
 * fact about an account the company does not own.
 */
@Service
@RequiredArgsConstructor
public class CompanyVendorService {

    private final CompanyVendorRepository companyVendorRepository;
    private final CompanyVendorLookup companyVendorLookup;
    private final ClientRepository clientRepository;
    private final VendorPurchaseService vendorPurchaseService;

    /**
     * Both kinds in one page, optionally narrowed and searched. Paged rather than
     * returned whole (unlike the address book): a company with a real supplier list
     * has hundreds of these, and every marketplace seller they ever buy from adds
     * one automatically.
     */
    @Transactional(readOnly = true)
    public Page<CompanyVendorResponse> list(CompanyVendorKind kind, String search, Pageable pageable) {
        return companyVendorRepository
                .findAll(CompanyVendorSpecifications.forTenant(requireTenantId(), kind, search), pageable)
                .map(CompanyVendorResponse::from);
    }

    /**
     * The vendor detail screen, in one round trip: the row, the live seller behind
     * it, the spend summary and the products supplied with their last price.
     *
     * <p>Purchase history is NOT part of this - it is paginated and lives on its
     * own screen, on the stakeholder's explicit instruction.
     */
    @Transactional(readOnly = true)
    public CompanyVendorDetailResponse get(UUID id) {
        CompanyVendor vendor = companyVendorLookup.require(id);
        return new CompanyVendorDetailResponse(
                CompanyVendorResponse.from(vendor),
                resolvePlatformVendor(vendor),
                vendorPurchaseService.spendSummary(vendor),
                vendorPurchaseService.suppliedProducts(vendor));
    }

    @Transactional
    public CompanyVendorResponse create(CompanyVendorRequest request) {
        validate(request);
        CompanyVendor vendor = CompanyVendor.builder()
                // Not taken from the request, and there is no field for it there:
                // this endpoint creates external suppliers and nothing else.
                .vendorKind(CompanyVendorKind.EXTERNAL)
                .name(request.name().trim())
                .contactPhone(request.contactPhone().trim())
                .email(blankToNull(request.email()))
                .addressLine1(blankToNull(request.addressLine1()))
                .addressLine2(blankToNull(request.addressLine2()))
                .city(blankToNull(request.city()))
                .state(blankToNull(request.state()))
                .notes(blankToNull(request.notes()))
                .bankName(blankToNull(request.bankName()))
                .bankAccountNumber(blankToNull(request.bankAccountNumber()))
                .bankAccountName(blankToNull(request.bankAccountName()))
                .cacNumber(blankToNull(request.cacNumber()))
                .active(true)
                .build();
        // saveAndFlush, not save: a CHECK violation has to surface inside this call
        // so CompanyVendorExceptionHandler can turn it into a 400 with a sentence.
        // Left to flush at commit it would escape the handler's reach and become a 500.
        return CompanyVendorResponse.from(companyVendorRepository.saveAndFlush(vendor));
    }

    @Transactional
    public CompanyVendorResponse update(UUID id, CompanyVendorRequest request) {
        CompanyVendor vendor = companyVendorLookup.require(id);
        requireEditable(vendor);
        validate(request);

        vendor.setName(request.name().trim());
        vendor.setContactPhone(request.contactPhone().trim());
        vendor.setEmail(blankToNull(request.email()));
        vendor.setAddressLine1(blankToNull(request.addressLine1()));
        vendor.setAddressLine2(blankToNull(request.addressLine2()));
        vendor.setCity(blankToNull(request.city()));
        vendor.setState(blankToNull(request.state()));
        vendor.setNotes(blankToNull(request.notes()));
        vendor.setBankName(blankToNull(request.bankName()));
        vendor.setBankAccountNumber(blankToNull(request.bankAccountNumber()));
        vendor.setBankAccountName(blankToNull(request.bankAccountName()));
        vendor.setCacNumber(blankToNull(request.cacNumber()));
        // vendorKind and platformClientId are untouched, and there is nothing in the
        // request that could touch them. An EXTERNAL row can never acquire a
        // platform_client_id through this endpoint because no code path here sets one.
        return CompanyVendorResponse.from(companyVendorRepository.saveAndFlush(vendor));
    }

    /**
     * The company removing a supplier from its directory. Allowed for both kinds -
     * see the class comment for why that is not the same permission as editing one.
     */
    @Transactional
    public void deactivate(UUID id) {
        CompanyVendor vendor = companyVendorLookup.require(id);
        vendor.setActive(false);
        companyVendorRepository.saveAndFlush(vendor);
        // Products keep pointing at the row rather than being unlinked here. The
        // link is history - "this is where that stock came from" - and blanking it
        // would destroy the only record of that on the buyer's own catalog. The row
        // simply stops appearing in the pickers, which is what removal means.
    }

    /**
     * The live {@code clients} row behind a VERIFIED entry. Loaded by primary key,
     * which is the one read that crosses the tenant boundary legitimately: Client
     * is not a tenant-scoped entity and has no filter to lift, so this is an
     * ordinary lookup rather than an exception to isolation - the same as
     * OrderLifecycleService resolving a buyer's name.
     *
     * <p>Only ever the client the row already points at, never one from a request
     * parameter, so a buyer cannot use this to read an arbitrary company's details.
     */
    private PlatformVendorSummary resolvePlatformVendor(CompanyVendor vendor) {
        if (vendor.getPlatformClientId() == null) {
            return null;
        }
        return clientRepository
                .findById(vendor.getPlatformClientId())
                .map(PlatformVendorSummary::from)
                .orElse(null);
    }

    /**
     * The guarantee this module is judged on: a VERIFIED entry can never be edited
     * by the company that owns it. Asked of the entity rather than restated as
     * {@code kind == EXTERNAL}, so the rule has exactly one definition.
     */
    private static void requireEditable(CompanyVendor vendor) {
        if (!vendor.isEditableByOwningCompany()) {
            throw new CompanyVendorNotEditableException(vendor.getName());
        }
    }

    private static void validate(CompanyVendorRequest request) {
        String state = request.state();
        if (state != null && !state.isBlank() && !NigerianStates.isValid(state)) {
            throw new InvalidCompanyVendorException(
                    "\"" + state + "\" is not a Nigerian state. Leave it blank if the supplier is elsewhere.");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static UUID requireTenantId() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context set");
        }
        return tenantId;
    }
}
