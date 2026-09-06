package com.procurepal_services.stock_bridge_api.purchase;

import com.procurepal_services.stock_bridge_api.purchase.dto.PurchaseHistoryEntry;
import com.procurepal_services.stock_bridge_api.purchase.dto.PurchaseSource;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Everything this company has bought" - across every supplier and both ledgers (marketplace
 * orders and manual stock-ins), one screen above the per-vendor purchase history on
 * {@code CompanyVendorController}.
 *
 * <p>Gated on {@code VIEW_VENDORS}, the same permission the per-vendor screen and the supplier
 * directory itself carry - reading what a company bought and from whom is the same authority
 * either way, and narrowing it further here would let a role see one supplier's history but not
 * the company-wide roll-up of the same facts.
 */
@RestController
@RequiredArgsConstructor
public class PurchaseHistoryController {

    private final PurchaseHistoryService purchaseHistoryService;

    /**
     * @param companyVendorId narrows to one supplier - this is what backs
     *     {@code /app/vendors/:id/purchases}. Omitted, this is the company-wide feed.
     * @param source narrows to one ledger; omitted returns both, merged and sorted together.
     */
    @GetMapping("/api/purchases")
    @PreAuthorize("hasAuthority('VIEW_VENDORS')")
    public Page<PurchaseHistoryEntry> search(
            @RequestParam(required = false) UUID companyVendorId,
            @RequestParam(required = false) PurchaseSource source,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @PageableDefault(size = 20) Pageable pageable) {
        return purchaseHistoryService.search(companyVendorId, source, from, to, pageable);
    }
}
