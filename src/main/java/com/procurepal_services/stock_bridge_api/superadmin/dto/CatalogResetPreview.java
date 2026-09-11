package com.procurepal_services.stock_bridge_api.superadmin.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * What a catalog reset would destroy for one tenant, or why it refuses to run.
 *
 * <p>Returned by both the dry run and the execute call, so the confirmation dialog and the
 * result screen read the same shape - and so "what we said would happen" and "what happened"
 * are directly comparable rather than two vocabularies for one operation.
 *
 * <h2>Blocked is an answer, not a failure</h2>
 * Same stance as {@code UndoOutcome}: a tenant whose products have been ordered gets a sentence
 * naming the products and a count, not a generic 409. The ops user can then go look at those
 * orders and decide, which is the only correct next step - a reset that quietly orphaned order
 * history would be worse than no reset at all.
 *
 * @param blocked whether the reset refused to run.
 * @param blockedReason which refusal this is, or null when not blocked. The two are not
 *     interchangeable: {@code ORDERED_PRODUCTS} is a data-integrity refusal with no way past it,
 *     while {@code ESTABLISHED_CUSTOMER} is a are-you-sure that a deliberate caller can
 *     acknowledge. A UI that renders them identically would either hide an escape hatch that
 *     exists or offer one that does not.
 * @param message the sentence. On a clean preview, what would go; on refusal, why it cannot.
 * @param blockers the products standing in the way, empty when not blocked.
 * @param counts what was (or would be) removed.
 * @param activity how long this tenant has really been running - see {@link Activity}.
 */
public record CatalogResetPreview(
        UUID clientId,
        String clientName,
        String clientSlug,
        boolean blocked,
        String blockedReason,
        String message,
        List<Blocker> blockers,
        Counts counts,
        Activity activity) {

    public CatalogResetPreview {
        blockers = List.copyOf(blockers);
    }

    /** Blocked because deleting these products would orphan real order lines. No way past it. */
    public static final String ORDERED_PRODUCTS = "ORDERED_PRODUCTS";

    /**
     * Blocked because this does not look like a botched onboarding. Passable, but only by a
     * caller that has seen the numbers and said so explicitly.
     */
    public static final String ESTABLISHED_CUSTOMER = "ESTABLISHED_CUSTOMER";

    /**
     * Whether this tenant looks like a fresh onboarding or a going concern.
     *
     * <p>The reset exists for a client who uploaded badly in their first week. Nothing about the
     * operation itself distinguishes that client from one who has been running inventory for a
     * year - both are "delete this tenant's products" - so the difference has to be measured and
     * put in front of whoever is about to press the button.
     *
     * <p>Measured from {@code stock_movements.created_at} rather than {@code occurred_at} or the
     * account's own age. occurred_at is backdatable and a bulk import of opening stock routinely
     * carries dates from months ago, which would make every new client look established on their
     * first upload. Account age fails the other way: a client who signed up in January and only
     * got round to uploading in March is still onboarding.
     *
     * @param firstActivityAt when this tenant first moved stock, or null if they never have.
     * @param daysActive days between that and now - 0 when there is no activity at all.
     * @param receivedOrders marketplace orders this tenant has actually taken delivery of. Even
     *     one means they have transacted, whatever the dates say.
     * @param established the verdict the reset acts on.
     */
    public record Activity(
            OffsetDateTime firstActivityAt, long daysActive, long receivedOrders, boolean established) {
    }

    /**
     * @param productId never rendered; used to link to the product that is in the way.
     * @param orderLines how many order lines reference it.
     */
    public record Blocker(UUID productId, String productName, String sku, long orderLines) {
    }

    /**
     * Every number the dialog shows. Split into what is deleted outright and what is merely
     * unlinked, because those are very different promises to make to an ops user: a deleted
     * product is gone, a cleared link leaves the other tenant's row intact.
     *
     * @param foreignCartLines lines in OTHER tenants' carts that reference this tenant's
     *     products. These vanish via {@code cart_items ON DELETE CASCADE} whether or not anyone
     *     looks at this number, which is exactly why it is surfaced before the button is
     *     pressed rather than reported afterwards.
     * @param orderLinksCleared past order lines whose {@code buyer_product_id} pointed at a
     *     product being deleted. The order survives; the link from it back into this tenant's
     *     inventory does not ({@code ON DELETE SET NULL}).
     * @param derivedProductLinksCleared other tenants' products created from one of this
     *     tenant's marketplace listings, whose {@code source_product_id} gets nulled.
     */
    public record Counts(
            long products,
            long activeProducts,
            long stockMovements,
            long stockAllocations,
            long productVendors,
            long importSessions,
            long companyVendors,
            long skuCounters,
            long foreignCartLines,
            long orderLinksCleared,
            long derivedProductLinksCleared) {
    }
}
