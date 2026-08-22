package com.procurepal_services.stock_bridge_api.superadmin;

import com.procurepal_services.stock_bridge_api.entity.VendorPayoutBatchStatus;
import com.procurepal_services.stock_bridge_api.security.SuperAdminPrincipal;
import com.procurepal_services.stock_bridge_api.settlement.VendorLedgerService;
import com.procurepal_services.stock_bridge_api.settlement.VendorSettlementService;
import com.procurepal_services.stock_bridge_api.settlement.VendorSettlementSettingsService;
import com.procurepal_services.stock_bridge_api.settlement.VendorStatementService;
import com.procurepal_services.stock_bridge_api.settlement.dto.EscrowHoldSettingsResponse;
import com.procurepal_services.stock_bridge_api.settlement.dto.MarkPayoutFailedRequest;
import com.procurepal_services.stock_bridge_api.settlement.dto.MarkPayoutPaidRequest;
import com.procurepal_services.stock_bridge_api.settlement.dto.PayoutBatchDetail;
import com.procurepal_services.stock_bridge_api.settlement.dto.PayoutBatchSummary;
import com.procurepal_services.stock_bridge_api.settlement.dto.PayoutRunPreview;
import com.procurepal_services.stock_bridge_api.settlement.dto.PayoutRunResponse;
import com.procurepal_services.stock_bridge_api.settlement.dto.ReverseAccrualRequest;
import com.procurepal_services.stock_bridge_api.settlement.dto.UpdateEscrowHoldRequest;
import com.procurepal_services.stock_bridge_api.settlement.dto.VendorStatementResponse;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The platform operator's settlement desk: review what is owed, run the biweekly
 * batches, record that the transfers happened, and reverse an order's accrual when
 * goods come back.
 *
 * <h2>How these are secured, which is not with {@code @PreAuthorize}</h2>
 * Nothing here carries a permission annotation, deliberately and in line with every
 * other super admin controller. Super admins are a separate principal type
 * ({@code SuperAdminPrincipal}, never a {@code TenantPrincipal}), they hold no
 * permission codes at all, and the entire {@code /api/superadmin/**} prefix is
 * gated in one place - {@code SecurityConfig} requires the super admin audience
 * authority on it, checked ahead of the general {@code /api/**} tenant rule. A
 * tenant token, ProcurePal's included, is refused before any handler runs. Adding
 * a {@code @PreAuthorize} here would be worse than redundant: it would suggest the
 * gate is per-route and invite somebody to add a route without one.
 *
 * <p>This class is registered in {@link SuperAdminExceptionHandler}'s
 * {@code assignableTypes} allow-list. That is not optional - a controller missing
 * from that list gets none of its handlers, and a 404 written as a 404 surfaces as
 * a 500.
 *
 * <h2>Why the payout run is here and not on a timer</h2>
 * The full argument is on {@code VendorSettlementService}. In short: there is no
 * automated disbursement anywhere in this system, so a scheduler would produce a
 * queue of payment instructions nobody had asked for, and each one would hold a
 * vendor's money out of the next run while looking like progress. The cadence is
 * still exact - {@code PayoutCadence} derives the cutoff from the calendar rather
 * than from when the button was pressed - so a late run pays the same money, just
 * later.
 *
 * <p>The documented commitment is <b>every other Monday, cutoff 00:00
 * Africa/Lagos</b>. {@code GET /runs/preview} says when the next one falls.
 *
 * <h2>Why the vendor statement is served here too</h2>
 * Because a payment dispute is answered by looking at the vendor's own statement,
 * and an operator who has to reconstruct it from the batch view will reconstruct it
 * differently. This route returns the identical record the vendor sees.
 */
@RestController
@RequestMapping("/api/superadmin/settlement")
@RequiredArgsConstructor
public class SuperAdminSettlementController {

    private final VendorSettlementService vendorSettlementService;
    private final VendorStatementService vendorStatementService;
    private final VendorLedgerService vendorLedgerService;
    private final VendorSettlementSettingsService vendorSettlementSettingsService;

    // ---------------------------------------------------------------------------------
    // Settlement policy: the escrow hold (M9)
    // ---------------------------------------------------------------------------------

    /**
     * The escrow hold currently in force, its bounds, and who last changed it.
     *
     * <p>A read, so no password and no acknowledgement - both belong to the write
     * below. Deliberately NOT exposed on any tenant surface: a vendor is told the hold
     * that applies to their own money through their statement's escrow position, which
     * carries {@code escrowHoldDays} and the actual maturity dates, and that is a more
     * useful answer than the setting itself. Nobody outside ProcurePaddy needs to be
     * able to poll the platform's policy.
     */
    @GetMapping("/settings")
    public EscrowHoldSettingsResponse settings() {
        return vendorSettlementSettingsService.settings();
    }

    /**
     * Changes how long a vendor's confirmed money is held before it becomes
     * payout-eligible.
     *
     * <p><b>This applies to FUTURE accruals only.</b> The hold is stamped onto each
     * ledger entry at accrual and that table is append-only, so money a buyer has
     * already confirmed keeps the hold it was confirmed under and no date a vendor has
     * already been shown moves. The response says so in
     * {@code appliesToFutureAccrualsOnly}, and so does the email this sends.
     *
     * <p>PUT and not POST: the body carries the complete desired state of a single
     * named setting, and repeating the identical request is a no-op that writes
     * nothing and mails nobody - which is what PUT promises and POST does not.
     *
     * <h2>Three gates, all required</h2>
     * The super admin audience (in {@code SecurityConfig}, like every route here), the
     * caller's OWN password re-entered in the body, and an explicit
     * {@code acknowledged: true}. A wrong password is a 403 whose message
     * distinguishes nothing; a missing acknowledgement is a 400 that says exactly what
     * is missing. See {@code VendorSettlementSettingsService} for why all three are
     * needed and why none substitutes for another.
     *
     * <p>The actor comes from the authenticated principal and never from the body. A
     * body-supplied actor would let somebody re-authenticate as a different super
     * admin with a password they had guessed, which is precisely the attack the
     * password gate exists to close.
     */
    @PutMapping("/settings/escrow-hold")
    public EscrowHoldSettingsResponse changeEscrowHold(
            @Valid @RequestBody UpdateEscrowHoldRequest request,
            @AuthenticationPrincipal SuperAdminPrincipal principal) {
        return vendorSettlementSettingsService.changeEscrowHold(request, principal.getSuperAdminId());
    }

    // ---------------------------------------------------------------------------------
    // The run
    // ---------------------------------------------------------------------------------

    /**
     * What a run made now would produce, per vendor, without writing anything.
     * Includes vendors who will be SKIPPED and says why, because "where is this
     * vendor" is the question this screen exists to answer.
     */
    @GetMapping("/runs/preview")
    public PayoutRunPreview preview() {
        return vendorSettlementService.preview();
    }

    /**
     * Creates one PENDING batch per eligible vendor at the current cutoff.
     *
     * <p>POST because it writes, and emphatically not idempotent in the HTTP sense -
     * except that it is, in the way that matters: a second call finds a live batch
     * for the same vendor and cutoff and reports it as skipped rather than creating
     * a second instruction to pay the same money. Two unique indexes back that up
     * for the race the check cannot cover.
     *
     * @param sellerId narrow the run to one vendor - for re-running after a failed
     *     transfer without touching the other fourteen.
     */
    @PostMapping("/runs")
    public PayoutRunResponse run(
            @RequestParam(required = false) UUID sellerId, @AuthenticationPrincipal SuperAdminPrincipal principal) {
        return vendorSettlementService.run(sellerId, principal.getSuperAdminId());
    }

    // ---------------------------------------------------------------------------------
    // Batches
    // ---------------------------------------------------------------------------------

    @GetMapping("/batches")
    public Page<PayoutBatchSummary> batches(
            @RequestParam(required = false) UUID sellerId,
            @RequestParam(required = false) VendorPayoutBatchStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return vendorSettlementService.list(sellerId, status, pageable);
    }

    /** The audit view: which ledger lines went in, and whether they still add up to the frozen total. */
    @GetMapping("/batches/{id}")
    public PayoutBatchDetail batch(@PathVariable UUID id) {
        return vendorSettlementService.get(id);
    }

    /**
     * Records that a human made the transfer, and posts the {@code PAYOUT} ledger
     * entry that discharges the balance. The bank reference is required - it is the
     * only evidence this system will ever hold that a vendor was paid.
     */
    @PostMapping("/batches/{id}/mark-paid")
    public PayoutBatchSummary markPaid(
            @PathVariable UUID id,
            @Valid @RequestBody MarkPayoutPaidRequest request,
            @AuthenticationPrincipal SuperAdminPrincipal principal) {
        return vendorSettlementService.markPaid(id, request, principal.getSuperAdminId());
    }

    /**
     * Records that the transfer did not happen. Posts nothing to the ledger - no
     * money moved - and releases the batch's lines back into the next run.
     */
    @PostMapping("/batches/{id}/mark-failed")
    public PayoutBatchSummary markFailed(
            @PathVariable UUID id,
            @Valid @RequestBody MarkPayoutFailedRequest request,
            @AuthenticationPrincipal SuperAdminPrincipal principal) {
        return vendorSettlementService.markFailed(id, request, principal.getSuperAdminId());
    }

    // ---------------------------------------------------------------------------------
    // Corrections
    // ---------------------------------------------------------------------------------

    /**
     * Reverses a delivered order's accrual - the refund, return and post-delivery
     * cancellation path.
     *
     * <p>Posts opposite-signed rows for the proceeds AND the commission; nothing is
     * edited or deleted. If the vendor has already been paid, their balance simply
     * goes negative and no batch is created for them until new sales clear it - the
     * clawback behaviour VENDOR_RESEARCH.md Section A asks for, with no extra state.
     *
     * <p>409 when the order never accrued, rather than a quiet success: reversing an
     * order that earned nothing is almost always somebody acting on the wrong order.
     */
    @PostMapping("/orders/{orderId}/reverse")
    public Map<String, Object> reverse(
            @PathVariable UUID orderId,
            @Valid @RequestBody ReverseAccrualRequest request,
            @AuthenticationPrincipal SuperAdminPrincipal principal) {
        int entries = vendorSettlementService.reverseOrder(orderId, request, principal.getSuperAdminId());
        return Map.of("orderId", orderId, "reversalEntries", entries);
    }

    // ---------------------------------------------------------------------------------
    // Reads about one vendor
    // ---------------------------------------------------------------------------------

    /**
     * One vendor's statement, identical to what that vendor sees.
     *
     * <p>The seller id is a path variable here, which it never is on the vendor
     * surface. That is safe for exactly one reason and it is worth naming: this route
     * is behind the super admin audience gate, and a super admin legitimately reads
     * across every tenant. The vendor-facing service method takes no seller id at
     * all, so there is no shared parameter for a missing null-check to expose.
     */
    @GetMapping("/vendors/{sellerId}/statement")
    public VendorStatementResponse vendorStatement(
            @PathVariable UUID sellerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return vendorStatementService.statementForSeller(sellerId, from, to);
    }

    /**
     * Runs the escrow release sweep once, now.
     *
     * <p>The sweep is normally a {@code @Scheduled} job that is off by default (see
     * {@code VendorSettlementSchedulingConfig} for why a money-writing job should be
     * opted into). This is the manual handle for an operator on a deployment where
     * it is switched off, and for the case where somebody wants it to have happened
     * before running a batch rather than waiting up to an hour.
     *
     * <p>Safe to call repeatedly: the accrual it performs is keyed per order line, so
     * a second call finds nothing left to release.
     */
    @PostMapping("/escrow/release")
    public Map<String, Object> releaseEscrow() {
        return Map.of("ordersReleased", vendorLedgerService.releaseUnconfirmedDeliveries());
    }
}
