package com.procurepal_services.stock_bridge_api.settlement;

import com.procurepal_services.stock_bridge_api.email.EmailNotificationService;
import com.procurepal_services.stock_bridge_api.entity.SuperAdmin;
import com.procurepal_services.stock_bridge_api.entity.VendorSettlementSettings;
import com.procurepal_services.stock_bridge_api.entity.VendorSettlementSettingsChange;
import com.procurepal_services.stock_bridge_api.repository.SuperAdminRepository;
import com.procurepal_services.stock_bridge_api.repository.VendorSettlementSettingsChangeRepository;
import com.procurepal_services.stock_bridge_api.repository.VendorSettlementSettingsRepository;
import com.procurepal_services.stock_bridge_api.settlement.dto.EscrowHoldChangeEntry;
import com.procurepal_services.stock_bridge_api.settlement.dto.EscrowHoldSettingsResponse;
import com.procurepal_services.stock_bridge_api.settlement.dto.UpdateEscrowHoldRequest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading and changing the escrow hold - the one settlement rule a platform operator
 * can move at runtime, and the most consequential setting in the application.
 *
 * <h2>THE RULE, first, because it is the first thing anybody asks</h2>
 * <b>A change applies to FUTURE accruals only.</b> The hold is stamped onto
 * {@code vendor_ledger_entries.matures_at} at accrual, and that table is append-only,
 * so a sale keeps the hold that was in force when its buyer confirmed it. Raising the
 * hold does not push back money a vendor has already been shown a date for; lowering
 * it does not pull that money forward either.
 *
 * <p>The alternative - computing maturity from the current setting at query time -
 * was considered and rejected in V15's header. In one sentence: a computed predicate
 * makes every change silently retroactive, so raising the hold from 7 to 30 would
 * move every vendor's already-promised payment date three weeks into the future with
 * no row anywhere recording that it moved. That is the worst failure available here,
 * because it is invisible to us and extremely visible to them.
 *
 * <p>Either behaviour is defensible in the abstract. What is not defensible is
 * shipping one while an operator assumes the other, so this rule is stated on the
 * column, on the migration, on the entity, in the API response
 * ({@code appliesToFutureAccrualsOnly}), in the email that announces a change, and on
 * the screen above the submit button.
 *
 * <h2>Three independent gates on the change, and what each one is for</h2>
 * <ol>
 *   <li><b>Super admin.</b> {@code /api/superadmin/**} is gated in
 *       {@code SecurityConfig} on the super admin audience, ahead of the general
 *       {@code /api/**} tenant rule, so a tenant token - ProcurePal's included - is
 *       refused before any handler runs. Same arrangement as every other route on
 *       {@code SuperAdminSettlementController}, and the reason nothing here carries
 *       {@code @PreAuthorize}.</li>
 *   <li><b>The caller's own password, re-entered.</b> Verified against the
 *       {@code super_admins} row behind the authenticated principal with the same
 *       {@code PasswordEncoder} {@code SuperAdminAuthService.login} uses - reused, not
 *       reimplemented, because a second comparison is a second thing to get wrong and
 *       would eventually diverge on the encoder. This is what a bearer token cannot
 *       do: it catches a borrowed laptop and a stolen token, neither of which gate 1
 *       can see.</li>
 *   <li><b>An explicit acknowledgement.</b> {@code acknowledged: true} on the body,
 *       enforced by bean validation. It catches the one thing a password cannot: a
 *       super admin who genuinely is who they say they are and has not registered
 *       what the form does. An accidental form submit must not be able to change how
 *       money moves.</li>
 * </ol>
 * All three are required and none substitutes for another. A wrong password is a
 * clean 403 that reveals nothing (see
 * {@link SuperAdminReauthenticationFailedException}); a missing acknowledgement is a
 * 400 that says exactly what is missing, because there is nothing to protect there
 * and a vague refusal on a confirmation box just gets clicked again.
 *
 * <h2>Order of operations, which is not arbitrary</h2>
 * Re-authenticate, then read, then compare, then write, then audit, then email.
 * Nothing is written before the password is checked, so a failed attempt leaves the
 * database exactly as it found it. And nothing is emailed before the audit row
 * exists, so there is no world in which somebody is told about a change that has no
 * record - which would be worse than not being told, because it is a claim nobody can
 * check.
 *
 * <h2>A no-op is a success that writes nothing</h2>
 * Setting the hold to the value it already has commits no settings update, writes no
 * audit row and sends no email. It is not an error - the operator asked for a state
 * and got it - but it is not a CHANGE either, and an audit trail padded with entries
 * that changed nothing is one nobody reads.
 * {@code chk_vendor_settlement_settings_changes_actually_changed} makes that
 * structural rather than a habit.
 *
 * <h2>Email failure must never roll back the change</h2>
 * The notification is wrapped in a try/catch here even though
 * {@link EmailNotificationService} already contracts never to throw. That is belt and
 * braces and it is worth the four lines: by the time it runs, a money rule has been
 * changed and audited, and unwinding both because SES was unreachable would leave the
 * platform running a policy whose written record was deleted. The email is a courtesy
 * on top of a decision that has already been made; the decision is the thing that has
 * to survive.
 *
 * <p>Note the whole shape this depends on. {@code EmailDispatcher} defers the actual
 * send to {@code afterCommit} on another thread, and neither it nor
 * {@code EmailRecipients} is {@code @Transactional} - which is exactly what stops an
 * email problem from marking this transaction rollback-only. That trap is documented
 * at length on {@code EmailRecipients}; it has bitten this codebase before, on a
 * payment.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VendorSettlementSettingsService {

    /**
     * How much history the settings endpoint returns inline.
     *
     * <p>Bounded rather than paged because the table gains a row only when a human
     * changes a money rule, which is a handful of times a year: twenty is several
     * years of it, and an endpoint with paging controls would be UI for a page 2 that
     * will not exist for a decade. The audit table itself keeps everything - this is a
     * display limit, not a retention one, and the full history is a SQL query away for
     * the dispute that needs it.
     */
    private static final int RECENT_CHANGES = 20;

    private final VendorSettlementSettingsRepository settingsRepository;
    private final VendorSettlementSettingsChangeRepository changeRepository;
    private final SuperAdminRepository superAdminRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailNotificationService emailNotificationService;

    // ---------------------------------------------------------------------------------
    // Read
    // ---------------------------------------------------------------------------------

    /** The current hold, its bounds, and who last changed it. */
    @Transactional(readOnly = true)
    public EscrowHoldSettingsResponse settings() {
        VendorSettlementSettings settings = requireSettings();
        List<EscrowHoldChangeEntry> recent = changeRepository
                .findAllByOrderByChangedAtDesc(PageRequest.of(0, RECENT_CHANGES))
                .stream()
                .map(VendorSettlementSettingsService::toEntry)
                .toList();

        return new EscrowHoldSettingsResponse(
                settings.getEscrowHoldDays(),
                EscrowHoldPolicy.MIN_HOLD_DAYS,
                EscrowHoldPolicy.MAX_HOLD_DAYS,
                PayoutCadence.PERIOD_DAYS,
                // Always true. A field rather than an assumption - see the class doc.
                true,
                settings.getUpdatedAt(),
                recent.isEmpty() ? null : recent.getFirst(),
                recent);
    }

    // ---------------------------------------------------------------------------------
    // Change
    // ---------------------------------------------------------------------------------

    /**
     * Changes the hold, after proving the caller meant it.
     *
     * <p>The acknowledgement flag is enforced by bean validation on
     * {@link UpdateEscrowHoldRequest} rather than here, so it is refused before this
     * method is entered and there is no path into this code with it unset. The
     * password cannot be done that way - it needs the database - so it is the first
     * thing this method does, ahead of every read and every write.
     *
     * @param superAdminId the authenticated principal's id. Comes from
     *     {@code @AuthenticationPrincipal}, never from the request body: a body-supplied
     *     actor would let a caller re-authenticate as somebody else with a password they
     *     had guessed, which is the whole attack this gate is supposed to close.
     * @return the settings as they now stand, whether or not anything moved.
     * @throws SuperAdminReauthenticationFailedException 403, with a message that
     *     distinguishes nothing. See that class.
     */
    @Transactional
    public EscrowHoldSettingsResponse changeEscrowHold(UpdateEscrowHoldRequest request, UUID superAdminId) {
        SuperAdmin actor = requireReauthenticated(request.password(), superAdminId);

        VendorSettlementSettings settings = requireSettings();
        int previous = settings.getEscrowHoldDays();
        int next = request.holdDays();

        // Restated below bean validation, because a service should not depend on an
        // annotation on a DTO it does not own for a bound that decides when money
        // leaves. The third statement of the same rule is the CHECK on the table,
        // which is the only one that cannot be refactored away.
        if (!EscrowHoldPolicy.isInRange(next)) {
            throw new SettlementNotAllowedException("The escrow hold must be between "
                    + EscrowHoldPolicy.MIN_HOLD_DAYS + " and " + EscrowHoldPolicy.MAX_HOLD_DAYS + " days.");
        }

        if (previous == next) {
            // A success that changes nothing. No audit row, no email. See the class doc.
            log.info(
                    "Super admin {} submitted an escrow hold change to {} days, which is already the value in "
                            + "force. Nothing written.",
                    actor.getUsername(),
                    next);
            return settings();
        }

        OffsetDateTime changedAt = OffsetDateTime.now();
        settings.setEscrowHoldDays(next);

        // Written BEFORE the email, so there is no world in which somebody is told
        // about a change that has no record behind it.
        changeRepository.save(VendorSettlementSettingsChange.builder()
                .previousHoldDays(previous)
                .newHoldDays(next)
                .changedBy(actor.getId())
                // Snapshotted, so the row survives the account being deleted - the FK
                // is ON DELETE SET NULL precisely so the record outlives the operator.
                .changedByUsername(actor.getUsername())
                .reason(trimToNull(request.reason()))
                .changedAt(changedAt)
                .build());

        log.warn(
                "ESCROW HOLD CHANGED from {} to {} days by super admin {} ({}). Applies to future accruals only.",
                previous,
                next,
                actor.getUsername(),
                actor.getId());

        notifySuperAdmins(previous, next, actor.getUsername(), changedAt, request.reason());
        return settings();
    }

    // ---------------------------------------------------------------------------------
    // Plumbing
    // ---------------------------------------------------------------------------------

    /**
     * Proves the caller is holding their own password, not just their own token.
     *
     * <p>Reuses {@code PasswordEncoder} - the one bean {@code SuperAdminAuthService}
     * checks logins with - rather than comparing anything itself. Hand-rolling this
     * would be a second implementation of the single most security-sensitive
     * comparison in the application, and the two would eventually disagree the day
     * somebody changes the encoder.
     *
     * <p>Every failure is the same exception with the same message: a wrong password,
     * a principal whose row has since been deleted, anything. There is no username in
     * the request to leak, and a differentiated message would tell somebody holding a
     * borrowed token which of the two remaining obstacles they are up against.
     *
     * <p>The failure is logged at WARN with the id, and nothing is written. A failed
     * attempt deliberately does not go in the audit table:
     * {@code vendor_settlement_settings_changes} records CHANGES, and one that also
     * recorded attempts would be a table where the change is harder to find.
     */
    private SuperAdmin requireReauthenticated(String password, UUID superAdminId) {
        SuperAdmin actor = superAdminId == null
                ? null
                : superAdminRepository.findById(superAdminId).orElse(null);

        if (actor == null || password == null || !passwordEncoder.matches(password, actor.getPasswordHash())) {
            log.warn("Refused an escrow hold change: password re-entry failed for super admin {}.", superAdminId);
            throw new SuperAdminReauthenticationFailedException();
        }
        return actor;
    }

    /**
     * Tells every super admin, and cannot fail the caller.
     *
     * <p>{@link EmailNotificationService} already contracts never to throw and
     * {@code EmailDispatcher.dispatchQuietly} already absorbs a rendering failure.
     * This catch is a third layer, and it is here because of what is on the other side
     * of it: a money rule that has been changed and audited inside this transaction.
     * If anything at all escaped - a mock in a test, a bean somebody makes
     * {@code @Transactional} in two years, an OOM in a template - the change and its
     * audit row would both roll back, and the platform would go on running the OLD
     * hold while an operator had been told it changed. Silence about a change that
     * happened is a support ticket; a change that silently un-happened is a dispute.
     */
    private void notifySuperAdmins(
            int previous, int next, String changedByUsername, OffsetDateTime changedAt, String reason) {
        try {
            emailNotificationService.escrowHoldChanged(
                    previous, next, changedByUsername, changedAt, trimToNull(reason), PayoutCadence.PERIOD_DAYS);
        } catch (RuntimeException ex) {
            log.error(
                    "The escrow hold change from {} to {} days was applied and audited, but the notification to "
                            + "super admins could not be dispatched. The change stands.",
                    previous,
                    next,
                    ex);
        }
    }

    /**
     * The singleton row, or a loud failure.
     *
     * <p>V15 seeds it, so its absence is a broken database - and unlike
     * {@code EscrowHoldPolicy}, which degrades to a default because it is read on the
     * path a buyer confirms a delivery, this class is only ever reached by an operator
     * looking at a settings screen. Failing there tells the one person who can do
     * something about it, and inventing a row to update would hide a broken migration
     * behind a screen that appeared to work.
     */
    private VendorSettlementSettings requireSettings() {
        return settingsRepository
                .findBySingletonTrue()
                .orElseThrow(() -> new SettlementNotAllowedException(
                        "Settlement settings have not been initialised - vendor_settlement_settings has no row. "
                                + "V15 seeds it; this database has not run that migration or the row was removed."));
    }

    private static EscrowHoldChangeEntry toEntry(VendorSettlementSettingsChange change) {
        return new EscrowHoldChangeEntry(
                change.getId(),
                change.getPreviousHoldDays(),
                change.getNewHoldDays(),
                change.getChangedBy(),
                change.getChangedByUsername(),
                change.getReason(),
                change.getChangedAt());
    }

    /**
     * Blank and absent are the same thing for a reason field, and collapsing them here
     * means the audit row, the email and the API response all agree on which one it
     * was. A stored empty string would render as an empty "Reason given:" block.
     */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
