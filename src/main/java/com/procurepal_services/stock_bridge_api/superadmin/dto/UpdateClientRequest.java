package com.procurepal_services.stock_bridge_api.superadmin.dto;

import com.procurepal_services.stock_bridge_api.entity.PaymentTerms;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body of PUT /api/superadmin/clients/{id} - ProcurePal ops editing a
 * tenant's {@code clients} row, including ProcurePal's own.
 *
 * <h2>Relationship to UpdateCompanyRequest</h2>
 * This is the wider half of a deliberately split pair. UpdateCompanyRequest is
 * what a tenant may change about ITSELF (name, adminEmail, phone). This is what
 * ProcurePal may change ABOUT a tenant, and it is a strict superset: the same
 * three fields with byte-for-byte the same names and the same validation rules,
 * plus the two decisions that are ProcurePal's to make rather than the
 * customer's.
 *
 * The duplicated components are on purpose. Sharing one record between the two
 * surfaces would mean every field ops ever gains becomes tenant-writable the
 * moment it is added, which is precisely the fail-open trap UpdateCompanyRequest's
 * Javadoc describes. Two records that happen to agree can only drift by somebody
 * editing one of them, which is a code review; one record shared cannot be
 * narrowed at all. The rule for future edits is therefore: a field added here may
 * or may not belong there, but a field added there MUST be added here too, or the
 * customer can set something ops cannot see or correct.
 *
 * <h2>The two fields a tenant does not get</h2>
 * <ul>
 *   <li>{@code paymentTerms} - "a commercial relationship decision ProcurePal ops
 *       makes about a customer" (Client's Javadoc). Granting pay-on-delivery is
 *       extending credit, so it lives where the people who carry that risk work.
 *       Required rather than optional-means-unchanged: an ops form renders the
 *       current value, so a body that omits it is a client bug, and a 400 is a far
 *       better outcome than silently re-writing whatever happens to be in the
 *       record - in one direction that quietly revokes a customer's credit terms.</li>
 *   <li>{@code slug} - see below. This is the one field on this record that is
 *       optional, and the only one that can break something.</li>
 * </ul>
 *
 * <h2>Renaming the slug: what it costs</h2>
 * The slug is the client identifier users type at login; AuthService resolves it
 * with {@code findBySlug} on EVERY login. Changing it means, immediately and
 * without warning to anybody:
 * <ul>
 *   <li>every user of that company can still use their existing access token until
 *       it expires, but the moment they next sign in the old identifier is simply
 *       "unknown client" - support will hear about it as "the site says my company
 *       does not exist";</li>
 *   <li>every saved bookmark, onboarding email, runbook and password-manager entry
 *       that records the old identifier is wrong;</li>
 *   <li>renaming the platform owner away from {@code procurepal} additionally
 *       invalidates the identifier baked into the demo seed, APP_TOUR.md and the
 *       marketplace integration tests.</li>
 * </ul>
 * None of that is data loss and all of it is recoverable by renaming it back, which
 * is why this is allowed here at all - UpdateCompanyRequest's Javadoc says a rename
 * "belongs on the super-admin surface, where somebody can check what it breaks
 * first", and this is that surface. It is optional (null or absent = leave the
 * identifier alone) rather than replace-semantics like every other field here,
 * because there is no such thing as clearing a slug - the column is NOT NULL and
 * unique - so null cannot mean "empty" and can only usefully mean "not renaming".
 * That also means the overwhelmingly common edit (fix a typo in the display name,
 * update the billing contact) cannot log a company out by accident, which it could
 * if a form that forgot to populate the field sent {@code "slug": ""}.
 *
 * <h2>What is still not settable, here or anywhere</h2>
 * <ul>
 *   <li>{@code isPlatformOwner}. Two clients with the flag would split the public
 *       catalog in two, which is why {@code uq_clients_single_platform_owner} is a
 *       database index rather than a service check; and MOVING it re-points the
 *       entire marketplace at a different seller's inventory in one statement.
 *       Creating the platform owner is a bootstrap concern
 *       (PlatformOwnerBootstrapRunner, from environment variables), and moving it
 *       is a DBA one done deliberately with the app stopped. There is no REST verb
 *       for it and adding one would be a mistake.</li>
 *   <li>{@code isActive}. Suspension already has its own endpoint - PUT
 *       /api/superadmin/clients/{id}/status - and it is separate precisely because
 *       suspending a paying customer is not the same kind of act as correcting
 *       their phone number. Folding it in here would let a routine profile save
 *       silently un-suspend a tenant somebody suspended for a reason.</li>
 * </ul>
 * Both are reported on SuperAdminClientDetail, so a caller that sends either one
 * gets a 200 with the real, unchanged value echoed back rather than silence - the
 * same "the answer is no, not nothing" behaviour CompanyIntegrationTest asserts for
 * the tenant surface.
 */
public record UpdateClientRequest(
        // Sizes mirror the columns exactly (clients.name VARCHAR(255),
        // admin_contact_email VARCHAR(255), phone VARCHAR(50)) so an over-long
        // value is a readable 400 rather than a Postgres 22001 surfacing as a 500.
        @NotBlank @Size(max = 255) String name,
        // Named adminEmail, not adminContactEmail: this is the vocabulary already
        // on the wire everywhere else (ClientSignupRequest.adminEmail,
        // SuperAdminClientSummary.adminEmail, UpdateCompanyRequest.adminEmail).
        // Only the column is called admin_contact_email.
        @NotBlank @Email @Size(max = 255) String adminEmail,
        // Optional, and length-only on purpose - the same reasoning
        // ClientSignupRequest and UpdateCompanyRequest give: Nigerian numbers get
        // typed as 0803..., +234 803..., with spaces and with dashes, and
        // rejecting a company's own phone number over formatting would be a
        // self-inflicted wound. Blank means the ops user cleared it, so it is
        // stored as NULL.
        @Size(max = 50) String phone,
        @NotNull PaymentTerms paymentTerms,
        // Optional; null/absent means "do not rename". The pattern is the same
        // shape ClientSignupService.slugify produces, enforced here because
        // nothing slugifies this input for you: it is typed by an ops user, and a
        // slug with a space or an uppercase letter in it would be un-typeable at
        // the login form it exists to serve. Max 100 rather than the column's
        // unbounded VARCHAR because an identifier somebody has to type is already
        // far too long at 100 characters.
        @Size(max = 100)
                @Pattern(
                        regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
                        message = "must contain only lowercase letters, digits and single hyphens between them")
                String slug) {
}
