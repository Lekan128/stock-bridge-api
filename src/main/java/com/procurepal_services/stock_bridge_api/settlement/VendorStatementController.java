package com.procurepal_services.stock_bridge_api.settlement;

import com.procurepal_services.stock_bridge_api.settlement.dto.VendorStatementResponse;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A SELLER's own account statement - a vendor's, or ProcurePal's (empty, and
 * explained). The surface the stakeholder asked for: accumulated fees, and what
 * they will be paid.
 *
 * <h2>hasAnyAuthority, and why that is not a loosening</h2>
 * The same reasoning as {@code VendorSalesAnalyticsController}, which should be
 * read first. VIEW_OWN_SALES_ANALYTICS was minted for the VENDOR role;
 * ProcurePal's own staff hold VIEW_MARKETPLACE_ANALYTICS instead, and ProcurePal is
 * a seller. Requiring only the new code would lock the platform owner out of a
 * selling surface - the {@code requireVendor()} trap - and requiring only the old
 * one would put us back where V11 started. So this accepts either and leans on
 * {@code VendorGuard.requireSeller()} for the real gate, with a
 * {@code seller_client_id} predicate doing the actual isolation.
 *
 * <p>Accepting VIEW_MARKETPLACE_ANALYTICS is therefore not a way in for a buying
 * company's OWNER, who also holds it: the guard refuses them with a 403 saying
 * their account does not sell.
 *
 * <h2>No seller id anywhere in the signature</h2>
 * Neither route takes one, and neither should ever grow one. The seller comes only
 * from the guard. The operator's equivalent - "show me THIS vendor's statement" -
 * lives on the super admin surface behind a different principal entirely, rather
 * than as an optional parameter here that would be one missing null-check away
 * from a cross-vendor leak.
 *
 * <h2>Export is CSV, deliberately not PDF</h2>
 * A PDF needs a library, a font and a layout, and none of it makes the arithmetic
 * easier to check. CSV opens in the spreadsheet a small business's bookkeeper is
 * already using and lets them re-add the column themselves, which is exactly the
 * property this statement was asked to have. The screen is print-styled for the
 * "something to file" case.
 */
@RestController
@RequestMapping("/api/vendor/statement")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('VIEW_OWN_SALES_ANALYTICS','VIEW_MARKETPLACE_ANALYTICS')")
public class VendorStatementController {

    private final VendorStatementService vendorStatementService;

    /**
     * {@code from}/{@code to} are ISO offset date-times, both optional, defaulting to
     * month-to-date - matching the seller's own sales screen so the two open on the
     * same period. The window is half-open, {@code [from, to)}.
     */
    @GetMapping
    public VendorStatementResponse statement(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return vendorStatementService.statementFor(from, to);
    }

    /**
     * The same statement as a downloadable CSV.
     *
     * <p>A separate path rather than content negotiation on the route above: the
     * browser has to be able to reach it as a plain link with a download attribute,
     * and an {@code Accept} header is not something an anchor tag can set. The
     * filename carries the period so a vendor with a folder of these can tell them
     * apart.
     */
    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<String> export(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        VendorStatementResponse statement = vendorStatementService.statementFor(from, to);
        String filename = "procurepaddy-statement-"
                + DateTimeFormatter.ISO_LOCAL_DATE.format(statement.from())
                + "-to-"
                + DateTimeFormatter.ISO_LOCAL_DATE.format(statement.to())
                + ".csv";

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(VendorStatementService.toCsv(statement));
    }
}
