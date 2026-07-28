package com.procurepal_services.stock_bridge_api.company.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The body of PUT /api/company, and the whole of the security boundary for
 * tenant self-editing.
 *
 * <h2>This record IS the allow-list</h2>
 * The obvious implementation - accept a Client (or a map) and filter out the
 * fields a tenant may not touch - fails open: a column added to clients later is
 * writable the moment it exists, because nobody remembers to extend the deny
 * list. Binding a purpose-built record fails closed instead. A new column is
 * un-writable until somebody deliberately adds a component here, which is a code
 * review nobody can skip.
 *
 * What that keeps out of reach, and why each one matters:
 * <ul>
 *   <li>{@code isPlatformOwner} - a tenant declaring itself the marketplace
 *       operator would take ownership of the public catalog. ClientSignupService
 *       states this rule for signup ("a self-service tenant cannot declare
 *       itself the marketplace operator"); the same rule has to hold after
 *       signup or it was never a rule.</li>
 *   <li>{@code paymentTerms} - gates pay-on-delivery at checkout, and per
 *       Client's Javadoc it is "a commercial relationship decision ProcurePal
 *       ops makes about a customer". A tenant granting itself credit terms takes
 *       goods it has not paid for.</li>
 *   <li>{@code isActive} - suspension is a super-admin action
 *       (SuperAdminClientService.updateStatus). A suspended tenant that can
 *       un-suspend itself was never suspended.</li>
 *   <li>{@code slug} - the login identifier: unique, resolved by findBySlug on
 *       every login. Changing it logs out every user of the company
 *       simultaneously and invalidates every stored reference to them. It stays
 *       immutable here even though a rename request is a plausible support ask -
 *       that belongs on the super-admin surface, where somebody can check what
 *       it breaks first.</li>
 * </ul>
 *
 * Unknown JSON properties are dropped by Jackson rather than rejected (Spring
 * Boot disables FAIL_ON_UNKNOWN_PROPERTIES globally, and {@code @JsonIgnore
 * Properties(ignoreUnknown = false)} cannot re-enable it for one type - the
 * annotation can only widen what is ignored, never narrow it). A caller that
 * sends {@code paymentTerms} therefore gets a 200 - but the CompanyResponse it
 * gets back carries the real, unchanged value, so the answer is "no", not
 * silence. CompanyIntegrationTest asserts exactly that.
 *
 * <h2>Replace semantics</h2>
 * Like UpdateProfileRequest and unlike UpdateUserRequest's patch semantics: this
 * is a PUT from a settings form that always renders every field, so a blank
 * phone means the user cleared it and it is stored as NULL. name and adminEmail
 * are NOT NULL columns, so both are required - there is no way for a form to
 * mean "clear the company's name".
 */
public record UpdateCompanyRequest(
        // Sizes mirror the columns exactly (clients.name VARCHAR(255),
        // admin_contact_email VARCHAR(255), phone VARCHAR(50)) so an over-long
        // value is a readable 400 rather than a Postgres 22001 surfacing as a 500.
        @NotBlank @Size(max = 255) String name,
        // Required and format-checked, unlike the user-profile email: this is the
        // address ProcurePal's account correspondence goes to, and a company with
        // an unreachable contact of record is a support problem waiting to happen.
        // Named adminEmail rather than adminContactEmail to match the vocabulary
        // already on the wire (ClientSignupRequest.adminEmail,
        // SuperAdminClientDetail.adminEmail); only the column is called
        // admin_contact_email.
        @NotBlank @Email @Size(max = 255) String adminEmail,
        // Optional, and length-only on purpose - the same reasoning
        // ClientSignupRequest gives: Nigerian numbers get typed as 0803..., +234
        // 803..., with spaces and with dashes, and rejecting a company's own phone
        // number over formatting would be a self-inflicted wound.
        @Size(max = 50) String phone) {
}
