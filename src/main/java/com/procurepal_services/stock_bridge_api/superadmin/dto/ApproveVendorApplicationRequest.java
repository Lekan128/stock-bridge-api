package com.procurepal_services.stock_bridge_api.superadmin.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * The body of POST /api/superadmin/vendor-waitlist/{id}/approve - the request that
 * turns an application into a working vendor login.
 *
 * <h2>Why this is thinner than CreateVendorRequest</h2>
 * Everything the applicant already told us is taken from the application row, not
 * from this body: business name, email, phone and the four address columns. Asking
 * an ops user to retype them would be asking them to introduce typos into fields
 * we have verbatim, and - worse - would let the approved account quietly disagree
 * with the application it claims to have come from, at which point
 * {@code approved_client_id} stops meaning what it was created to mean.
 *
 * <p>What is left is the three things an application genuinely cannot supply.
 *
 * <h2>The three</h2>
 * <ul>
 *   <li>{@code username} and {@code password} - credentials, which no applicant
 *       chooses for themselves in this flow. The password is confirmed for the
 *       reason {@code ClientSignupRequest} gives, and is hashed on arrival, never
 *       returned and never emailed.</li>
 *   <li>{@code commissionRate} - a commercial term negotiated with the business,
 *       optional because it is frequently agreed after onboarding.</li>
 *   <li>{@code reviewNote} - optional here and REQUIRED on rejection, and the
 *       asymmetry is deliberate. A rejection's note is the body of the email the
 *       applicant receives, so an empty one sends somebody a decision with no
 *       reason. An approval's note is an internal aside ("approved, but only for
 *       packaging") that {@code vendor_waitlist_applications.review_note} keeps
 *       precisely so it can be read back; when present it is also quoted to the
 *       applicant, so it should read as something they may see.</li>
 * </ul>
 *
 * <h2>Why the slug is settable but the name is not</h2>
 * The name comes from the application because that is the business's own answer to
 * "what are you called". The slug is not something they were asked for at all - it
 * is the identifier their staff type at login - so it has to be derivable here,
 * and overridable when the derived one collides with an existing client or reads
 * badly. Left blank it is derived from the business name exactly as at signup.
 */
public record ApproveVendorApplicationRequest(
        @NotBlank @Size(max = 255) String username,
        @NotBlank @Size(min = 8) String password,
        @NotBlank String confirmPassword,
        // Optional; derived from the application's business name when blank. Same
        // pattern and same reasoning as UpdateClientRequest.slug.
        @Size(max = 100)
                @Pattern(
                        regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
                        message = "must contain only lowercase letters, digits and single hyphens between them")
                String clientIdentifier,
        /** A fraction in 0..1, matching chk_clients_commission_rate_fraction. */
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal commissionRate,
        /** Optional - see the class doc on why rejection's equivalent is not. */
        @Size(max = 500) String reviewNote) {
}
