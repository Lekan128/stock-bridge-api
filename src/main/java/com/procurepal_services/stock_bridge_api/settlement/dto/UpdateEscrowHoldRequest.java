package com.procurepal_services.stock_bridge_api.settlement.dto;

import com.procurepal_services.stock_bridge_api.settlement.EscrowHoldPolicy;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Changing how long a vendor's money is held before it can be paid.
 *
 * <h2>Three things have to be true before this moves anything</h2>
 * The owner asked for a change that a super admin has to mean, and the three
 * requirements below are independent on purpose - each blocks a different mistake:
 * <ol>
 *   <li><b>Being signed in as a super admin</b> ({@code /api/superadmin/**}, gated in
 *       {@code SecurityConfig}). Blocks everybody else.</li>
 *   <li><b>{@link #password}</b>, re-entered and checked against the CALLER's own
 *       credentials. Blocks a borrowed laptop and a stolen access token, neither of
 *       which the gate above can see. Verified with the same
 *       {@code PasswordEncoder} the login path uses - see
 *       {@code VendorSettlementSettingsService}.</li>
 *   <li><b>{@link #acknowledged}</b>. Blocks the one thing a password cannot: a super
 *       admin who genuinely is who they say they are and has not registered what the
 *       form does. This changes when real money leaves the business.</li>
 * </ol>
 * A password without the acknowledgement is refused and an acknowledgement without
 * the password is refused, so neither can be quietly dropped from a client and still
 * work.
 *
 * @param holdDays the new hold, in whole days. Bounded {@code [0, 90]} here, again in
 *     {@code EscrowHoldPolicy}, and again by a CHECK on the table - three layers,
 *     because a DTO can be bypassed by a future caller and a CHECK cannot. Zero is
 *     ALLOWED and means "payable the moment the buyer confirms"; see
 *     {@link EscrowHoldPolicy} for why that is a state worth keeping reachable.
 *     {@code @NotNull} rather than a primitive {@code int}, so an omitted field is a
 *     clear validation message instead of silently meaning zero - which on this field
 *     would be "pay everybody immediately".
 * @param password the caller's OWN super admin password. Never logged, never echoed,
 *     and never compared to anything but the row behind the authenticated principal -
 *     the request carries no username, so there is no account for a wrong guess to
 *     probe.
 * @param acknowledged must be present and {@code true}. Two annotations rather than a
 *     primitive: {@code @AssertTrue} alone treats null as valid (that is what the
 *     Bean Validation spec says), so a client that omitted the field entirely would
 *     sail through the one check whose whole job is to make sure somebody meant it.
 * @param reason optional free text, stored on the audit row and quoted in the email
 *     to the other super admins. Not mandatory, deliberately: a required reason field
 *     gets "asdf" typed into it, and an honest blank is worth more than a compulsory
 *     lie.
 */
public record UpdateEscrowHoldRequest(
        @NotNull(message = "holdDays is required.")
                @Min(value = EscrowHoldPolicy.MIN_HOLD_DAYS, message = "The hold cannot be negative.")
                @Max(value = EscrowHoldPolicy.MAX_HOLD_DAYS, message = "The hold cannot exceed 90 days.")
                Integer holdDays,
        @NotBlank(message = "Your password is required to change the escrow hold.") String password,
        @NotNull(message = "You must confirm that you understand what this changes.")
                @AssertTrue(message = "You must confirm that you understand what this changes.")
                Boolean acknowledged,
        @Size(max = 500, message = "The reason cannot exceed 500 characters.") String reason) {
}
