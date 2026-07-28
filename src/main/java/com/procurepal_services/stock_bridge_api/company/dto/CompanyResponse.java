package com.procurepal_services.stock_bridge_api.company.dto;

import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.PaymentTerms;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The caller's own company, as GET and PUT /api/company both return it.
 *
 * <h2>Three of these fields are strictly read-only</h2>
 * {@code clientIdentifier}, {@code platformOwner}, {@code paymentTerms} and
 * {@code active} are reported, never accepted. UpdateCompanyRequest has no
 * component for any of them and that is not an oversight - see its Javadoc for
 * what each one would cost if a tenant could set it. They are returned anyway
 * because the frontend genuinely needs them and has nowhere better to get them:
 * <ul>
 *   <li>{@code platformOwner} tells the app whether it is running as ProcurePal
 *       (marketplace-admin nav) or as a buyer. Read straight off the Client row,
 *       exactly like the JWT claim, TenantUserSummary.platformOwner and
 *       ProfileResponse.platformOwner - four readings of one column that must
 *       never disagree.</li>
 *   <li>{@code paymentTerms} is what lets a checkout screen show or hide the
 *       pay-on-delivery option before the user commits to it. It is only one of
 *       three gates (see MarketplaceSettings for the other two), so the UI must
 *       still expect the API to say no.</li>
 *   <li>{@code clientIdentifier} is the slug users type at login, worth showing
 *       on a settings page so somebody can tell a new colleague what it is.
 *       Named the way the login form names it (LoginRequest.clientIdentifier,
 *       ProfileResponse.clientIdentifier), not the way the column does.</li>
 *   <li>{@code active} is always true for anyone who can call this - a suspended
 *       tenant cannot authenticate (ClientSuspendedException) - so it is here for
 *       completeness and symmetry with the super-admin view rather than for the
 *       UI to branch on.</li>
 * </ul>
 * Echoing the authoritative values on the PUT response is also how a caller that
 * tried to smuggle one of them in the body finds out it did not take.
 */
public record CompanyResponse(
        UUID id,
        String name,
        String clientIdentifier,
        String adminEmail,
        String phone,
        boolean active,
        boolean platformOwner,
        PaymentTerms paymentTerms,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static CompanyResponse from(Client client) {
        return new CompanyResponse(
                client.getId(),
                client.getName(),
                client.getSlug(),
                client.getAdminContactEmail(),
                client.getPhone(),
                client.isActive(),
                client.isPlatformOwner(),
                client.getPaymentTerms(),
                client.getCreatedAt(),
                client.getUpdatedAt());
    }
}
