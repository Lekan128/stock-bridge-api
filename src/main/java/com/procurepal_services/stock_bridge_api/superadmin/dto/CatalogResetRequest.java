package com.procurepal_services.stock_bridge_api.superadmin.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * The body of {@code POST /api/superadmin/clients/{id}/catalog-reset}.
 *
 * <h2>Why a phrase has to be typed</h2>
 * Every other super-admin write is recoverable - a suspended tenant is unsuspended, a renamed
 * one is renamed back. This one is not: the products, the ledger behind them and the import
 * history all stop existing. {@code confirmPhrase} must equal {@code "delete <slug>"} for the
 * target tenant, so the operation cannot be fired by a mis-aimed curl, a replayed request, or
 * the wrong row in a list.
 *
 * <p>The tenant's own slug is in the phrase rather than a bare "delete" on purpose. A fixed
 * word is the same keystrokes on every tenant, which is exactly the muscle memory that deletes
 * the wrong account; including the slug means the caller has to have read which tenant they are
 * pointed at. The check lives on the server for the same reason: a dialog-only confirmation
 * protects the UI, not the endpoint.
 *
 * @param confirmPhrase must be "delete " + the target client's slug. Case and surrounding
 *     whitespace are forgiven; the slug itself is not.
 * @param acknowledgeEstablished required only when the tenant does not look like a fresh
 *     onboarding (see {@code CatalogResetPreview.Activity}). Absent or false, an established
 *     customer's reset is refused rather than performed - the accident this guards against is
 *     resetting a year-old account that happens to sit next to the new one in a list. A caller
 *     that has read those numbers and still means it sets this to true.
 * @param includeVendorDirectory also clear the suppliers this tenant added ({@code
 *     company_vendors}). Default false: the directory is usually typed by hand and worth
 *     keeping even when a bulk upload went wrong, but a botched onboarding often filled it with
 *     junk too, so it is offered rather than assumed.
 * @param resetSkuCounters also clear {@code product_sku_settings}, so auto-generated SKUs start
 *     from the beginning again instead of resuming above a sequence whose products no longer
 *     exist.
 */
public record CatalogResetRequest(
        @NotBlank(message = "Type the confirmation phrase to confirm this reset.") String confirmPhrase,
        boolean acknowledgeEstablished,
        boolean includeVendorDirectory,
        boolean resetSkuCounters) {

    /** What {@link #confirmPhrase} has to say for a given tenant. One definition, used by both sides. */
    public static String requiredPhraseFor(String slug) {
        return "delete " + slug;
    }

    public boolean confirms(String slug) {
        return confirmPhrase != null
                && confirmPhrase.trim().replaceAll("\\s+", " ").equalsIgnoreCase(requiredPhraseFor(slug));
    }
}
