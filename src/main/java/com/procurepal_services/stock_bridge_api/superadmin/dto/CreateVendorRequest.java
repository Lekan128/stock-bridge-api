package com.procurepal_services.stock_bridge_api.superadmin.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * The body of POST /api/superadmin/vendors - ProcurePal ops adding a vendor they
 * recruited offline, with no application behind it.
 *
 * <h2>Why email is optional here and required on the waitlist form</h2>
 * This is the single reason {@code chk_clients_company_has_contact_email} was
 * relaxed to cover COMPANY only. A business that reached us through the public
 * form is known to us by the address they left - it is their identifier and the
 * only way to reply, so {@code VendorWaitlistApplicationRequest} requires it. A
 * business an ops user met at a trade fair may genuinely have no email, and the
 * alternatives were both worse: refusing to onboard them at all, or writing a
 * synthetic placeholder into the column {@code EmailRecipients} reads - at which
 * point we send real mail to an address we invented. Blank is stored as NULL,
 * which is a state the mailer already understands as "nowhere to send".
 *
 * <p>The consequence is stated so nobody is surprised by it: a vendor with no
 * email receives no order notifications, which for a seller is a serious
 * degradation (VENDOR_RESEARCH.md section C item 8 - "the absence of vendor
 * notifications causes the most visible failure"). The admin UI should say so at
 * the point of entry. It is still better than the alternatives.
 *
 * <h2>Credentials, and what happens to them</h2>
 * {@code username} and {@code password} create the vendor's single user account.
 * The password is confirmed rather than taken once, on the same reasoning
 * {@code ClientSignupRequest} gives - a typo in a field nobody can read back
 * creates an account nobody can sign into - and is checked in the service against
 * {@code PasswordMismatchException}, which is already a clean 400 on this surface.
 *
 * <p>It is hashed on arrival and never returned, never logged, and deliberately
 * never emailed: {@code SuperAdminVendorDetail} echoes the username so ops can
 * tell the vendor what to log in as, and the approval email says the password
 * comes by another route. See {@code AccountEmails}' class doc for why that
 * inconvenience is the point.
 *
 * <h2>What is NOT settable, here or anywhere</h2>
 * {@code clientType} is not a field. A vendor created through this endpoint is
 * {@code VENDOR} because the endpoint says so, not because a body asked - the same
 * rule {@code CreateUserRequest} follows for {@code root}. Nor is
 * {@code isPlatformOwner} or {@code isActive}; see {@code UpdateClientRequest} for
 * both, whose reasoning is unchanged by vendors existing. Suspending a vendor uses
 * the existing PUT /api/superadmin/clients/{id}/status, because a vendor is a
 * client and suspension means exactly what it already meant.
 */
public record CreateVendorRequest(
        // Sizes mirror the columns exactly, so an over-long value is a readable 400
        // rather than a Postgres 22001 surfacing as a 500.
        @NotBlank @Size(max = 255) String name,
        // Optional - derived from the name when blank, exactly as at signup. When
        // given it is validated to the shape ClientProvisioning.slugify produces,
        // because nothing slugifies an ops user's typing for them and a slug with a
        // space in it is un-typeable at the login form it exists to serve.
        @Size(max = 100)
                @Pattern(
                        regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
                        message = "must contain only lowercase letters, digits and single hyphens between them")
                String clientIdentifier,
        // Optional. See the class doc - this is the whole reason the CHECK was
        // relaxed. Still format-checked when present: an address that is going to
        // be there should be one we can actually mail.
        @Email @Size(max = 255) String email,
        // Required, unlike on a COMPANY. A vendor with neither an email nor a phone
        // is a row nobody can reach, and at least one channel has to exist. Which
        // one is negotiable; having none is not. Length-only for the reason every
        // other phone field in this codebase gives.
        @NotBlank @Size(max = 50) String contactPhone,
        @Size(max = 255) String addressLine1,
        @Size(max = 255) String addressLine2,
        @Size(max = 100) String city,
        @Size(max = 100) String state,
        // A FRACTION, not a percentage: 0.15 is fifteen percent. Bounds mirror
        // chk_clients_commission_rate_fraction so a bad value is a 400 naming the
        // field rather than a 409 naming a constraint. Optional - a rate that has
        // not been agreed is null, which is different from zero (zero is a rate
        // somebody negotiated).
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal commissionRate,
        @NotBlank @Size(max = 255) String username,
        // Length-only for now; tighten later once there is a product decision on
        // password policy (see ClientSignupRequest for the same note).
        @NotBlank @Size(min = 8) String password,
        @NotBlank String confirmPassword) {
}
