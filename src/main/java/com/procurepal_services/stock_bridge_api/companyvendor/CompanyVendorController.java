package com.procurepal_services.stock_bridge_api.companyvendor;

import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorDetailResponse;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorResponse;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.VendorPurchaseResponse;
import com.procurepal_services.stock_bridge_api.entity.CompanyVendorKind;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A buying company's own supplier directory.
 *
 * <h2>company-vendors, not vendors</h2>
 * The path says which of the two things called "vendor" this is. A platform vendor
 * is a seller with an account, a catalogue and an order queue; a company vendor is
 * a row in one buyer's private list of who they buy from. V11__vendors.sql opens by
 * disambiguating the two and says confusing them is the most likely way to corrupt
 * this feature - so the URL disambiguates them too, rather than leaving
 * {@code /api/vendors} to be claimed by whichever module gets there first.
 *
 * <h2>Two permissions, split the way the money is</h2>
 * Reading is wider than writing, matching V11's seeding: VIEW_VENDORS goes to
 * OWNER, PROCUREMENT_MANAGER, FINANCE_OFFICER and INVENTORY_OFFICER, because
 * finance reconciles against what was paid and inventory needs to know where stock
 * came from. MANAGE_VENDORS is only OWNER and PROCUREMENT_MANAGER - maintaining the
 * supplier list is a procurement decision. STOREKEEPER holds neither, following the
 * same logic V6 used to keep prices and spend away from them.
 *
 * <p>Permission codes are literal strings because this repo has no constants class
 * for them, and every other controller here does the same.
 *
 * <h2>DELETE is a deactivation</h2>
 * It answers 204 and the row leaves the list, which is indistinguishable from a
 * removal for every caller. See CompanyVendorService for why it is unconditional
 * rather than "only when the vendor has history".
 */
@RestController
@RequestMapping("/api/company-vendors")
@RequiredArgsConstructor
public class CompanyVendorController {

    private final CompanyVendorService companyVendorService;
    private final VendorPurchaseService vendorPurchaseService;
    private final CompanyVendorLookup companyVendorLookup;

    /**
     * The directory list. Both kinds together by default - a buyer's supplier list
     * is one list, and VERIFIED vs EXTERNAL is a fact about where a row came from,
     * not a category of business they want to filter by before they have looked.
     *
     * @param kind optional narrowing to one kind
     * @param search matched against the vendor's name
     */
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_VENDORS')")
    public Page<CompanyVendorResponse> list(
            @RequestParam(required = false) CompanyVendorKind kind,
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
        return companyVendorService.list(kind, search, pageable);
    }

    /**
     * The vendor detail screen: the row, the live seller behind it, spend to date
     * and the products supplied with their last purchase price.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_VENDORS')")
    public CompanyVendorDetailResponse get(@PathVariable UUID id) {
        return companyVendorService.get(id);
    }

    /**
     * Purchase history, on its own endpoint feeding its own screen - the
     * stakeholder asked for it separately and it is paginated, so folding it into
     * the detail response would either truncate it silently or make the detail
     * screen pay for a page nobody scrolled to.
     *
     * <p>Always empty for an EXTERNAL vendor, by definition rather than by
     * accident: they have no orders on this platform. The screen says so.
     */
    @GetMapping("/{id}/purchases")
    @PreAuthorize("hasAuthority('VIEW_VENDORS')")
    public Page<VendorPurchaseResponse> purchases(
            @PathVariable UUID id, @PageableDefault(size = 20) Pageable pageable) {
        return vendorPurchaseService.purchaseHistory(companyVendorLookup.require(id), pageable);
    }

    /**
     * Adds an EXTERNAL supplier. There is no endpoint for adding a VERIFIED one and
     * there must never be: VERIFIED asserts that a purchase really happened, and a
     * buyer typing one in would be asserting it about somebody else's account. They
     * are written only by CompanyVendorLinkService, on a real order.
     */
    @PostMapping
    @PreAuthorize("hasAuthority('MANAGE_VENDORS')")
    public ResponseEntity<CompanyVendorResponse> create(@Valid @RequestBody CompanyVendorRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(companyVendorService.create(request));
    }

    /**
     * Edits an EXTERNAL supplier. A VERIFIED one answers 409 - see
     * CompanyVendorNotEditableException for why that, and not 403.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('MANAGE_VENDORS')")
    public CompanyVendorResponse update(
            @PathVariable UUID id, @Valid @RequestBody CompanyVendorRequest request) {
        return companyVendorService.update(id, request);
    }

    /**
     * Removes a supplier from the directory. Allowed for BOTH kinds, unlike edit:
     * "stop showing me this supplier" is the company's opinion about its own list,
     * where "this supplier is called something else" is a claim about an account it
     * does not own.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('MANAGE_VENDORS')")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        companyVendorService.deactivate(id);
        return ResponseEntity.noContent().build();
    }
}
