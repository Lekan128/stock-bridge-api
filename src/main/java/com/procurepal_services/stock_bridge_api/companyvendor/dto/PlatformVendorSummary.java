package com.procurepal_services.stock_bridge_api.companyvendor.dto;

import com.procurepal_services.stock_bridge_api.entity.Client;
import java.util.UUID;

/**
 * The live {@code clients} row behind a VERIFIED directory entry, resolved on the
 * detail read only.
 *
 * <h2>This is the answer to the rename-staleness question M1 left open</h2>
 * {@code company_vendors.name} is a snapshot, and it goes stale the moment a
 * platform vendor renames itself. The two candidate fixes were "snapshot" and
 * "resolve on read", and this module takes snapshot - but with the staleness
 * bounded rather than merely accepted:
 *
 * <ul>
 *   <li>The <b>list</b> shows the snapshot, always. Resolving there would put a
 *       COALESCE over an OUTER JOIN in front of every sort, search and page,
 *       which is exactly what V11__vendors.sql refused to do.</li>
 *   <li>The <b>detail</b> screen shows this. It already has to load the seller's
 *       row to show the authoritative phone, email and address of a vendor the
 *       buyer may not edit, so the live name costs nothing extra - and a stale
 *       list entry becomes correct the moment somebody opens it.</li>
 *   <li>The <b>next purchase</b> rewrites the snapshot. See
 *       {@code CompanyVendorLinkService}, which refreshes it during the
 *       find-or-create so an active trading relationship self-heals.</li>
 * </ul>
 *
 * <p>What is deliberately NOT here: pushing the new name into every buyer's row
 * when the vendor renames itself. That is a cross-tenant bulk write triggered by
 * the vendor-profile-update endpoint, which belongs to the seller-side module and
 * does not exist yet. When it lands it must rewrite {@code company_vendors.name}
 * for every row pointing at that vendor, and until it does, the three points
 * above are what keeps a stale name from being seen for long.
 *
 * <p>Null for EXTERNAL entries, which have no clients row anywhere and must not
 * pretend to.
 */
public record PlatformVendorSummary(
        UUID clientId,
        /* The live name. Compare with CompanyVendorResponse.name to see a stale snapshot. */
        String name,
        String phone,
        String email,
        String city,
        String state,
        String logoUrl,
        boolean active) {

    public static PlatformVendorSummary from(Client client) {
        return new PlatformVendorSummary(
                client.getId(),
                client.getName(),
                client.getPhone(),
                client.getAdminContactEmail(),
                client.getCity(),
                client.getState(),
                client.getLogoUrl(),
                client.isActive());
    }
}
