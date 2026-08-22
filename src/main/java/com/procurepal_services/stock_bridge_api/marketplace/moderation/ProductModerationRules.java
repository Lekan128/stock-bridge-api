package com.procurepal_services.stock_bridge_api.marketplace.moderation;

import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.entity.ProductApprovalStatus;

/**
 * When a listing needs a human to look at it, and when it does not.
 *
 * <h2>The rule that keeps a buying company out of the queue</h2>
 * {@code products.approval_status} defaults to PENDING on EVERY row, which means every
 * buying company's private inventory is nominally PENDING too. V11 accepted that noise
 * deliberately (failing closed was the cheaper mistake) and left one obligation to
 * whoever built the workflow: <b>approval_status is only ever consulted for a product
 * being LISTED by a SELLER.</b>
 *
 * <p>That obligation is discharged in exactly two places, and they must stay in step:
 * this class, which decides what enters the queue, and
 * {@code MarketplaceProductSpecifications.listedBy}, which decides what leaves it for
 * the public catalog. A moderation queue that forgot the seller check would show a
 * super admin every restaurant's list of their own napkins and cooking oil, and asking
 * an operator to "approve" another company's private stock is both meaningless and a
 * cross-tenant disclosure.
 *
 * <h2>ProcurePal does not queue behind itself</h2>
 * The platform owner's products are stamped APPROVED at write time. Moderation exists
 * so the platform can vouch for a THIRD PARTY's listing before a buying company turns
 * it into a real purchase order; the platform vouching for itself is a no-op that would
 * only ever mean ProcurePal's own catalogue silently stopped rendering the first time
 * somebody forgot to clear the queue.
 *
 * <h2>Editing an APPROVED listing: identity re-moderates, price does not</h2>
 * This is the decision VENDOR_RESEARCH.md Section C item 4 forces and does not settle,
 * so it is settled here.
 *
 * <p>The risk item 4 names is "the first mis-priced or fake listing is a buying
 * company's real purchase order", and the way an approval gate gets defeated is
 * approve-then-swap: get a benign product cleared, then edit it into something else.
 * So <b>identity and content edits send a listing back to PENDING</b> - name, SKU,
 * description, brand, image, unit of measure. Those are the fields that determine what
 * a buyer thinks they are buying, and a change to them invalidates the judgement the
 * reviewer made.
 *
 * <p><b>Price and stock edits do NOT re-moderate.</b> Section A lists price/stock quick
 * edit as a MUST and Section C item 10 records that stale stock is Jumia's single
 * biggest cause of cancellations - the failure moderation is supposed to prevent. A
 * vendor who has to wait for review before correcting a quantity will stop correcting
 * quantities, and a vendor who cannot move a price in a market that moves daily will
 * list stale prices instead. Both outcomes are worse for buyers than the risk being
 * managed.
 *
 * <p>The contestable half of that is price, so the reasoning is explicit: a price
 * change does not misrepresent WHAT the goods are, and no buyer is ever committed to a
 * price they did not see - the cart stores no price, the quote and the order both
 * recompute from the live catalogue row, and the buyer confirms a total before
 * anything is placed (see CheckoutService). A name-and-image swap has no equivalent
 * safeguard, which is why the two are treated differently. If price moderation is ever
 * wanted, it should be a bounded rule (flag a change over N%) rather than a full
 * re-review, and it belongs next to this comment.
 *
 * <h2>Every write path that can reach a product row, and what it does about this</h2>
 * The rule above is only worth as much as its coverage, and M6 found it uncovered on one
 * path: {@code MarketplaceCatalogAdminService.updateMarketplaceDetails} wrote {@code brand}
 * and {@code unitOfMeasure} - two of the six fields named below - and never called
 * {@link ProductModerationService#onListingContentChanged}. This table is the audit that
 * found it, kept here so the next person adding a write path has a list to add themselves
 * to rather than a rule to rediscover.
 *
 * <p>M8 is the first entry to arrive that way, and it is worth recording that the mechanism
 * worked: the vendor marketplace-details route was added, the table gained a row, and the
 * new path re-triggers because it delegates to a method M6 had already fixed rather than
 * because anybody rediscovered the rule. Adding a write path means adding a row HERE and a
 * test in {@code ProductModerationWritePathIntegrationTest}, which is organised around this
 * table for exactly that reason.
 *
 * <table>
 *   <caption>Product write paths</caption>
 *   <tr><th>Path</th><th>Identity fields it writes</th><th>Behaviour</th></tr>
 *   <tr>
 *     <td>{@code ProductManagementService.update} - PUT /api/products/&#123;id&#125;</td>
 *     <td>name, sku, description, imageUrl</td>
 *     <td><b>Re-triggers.</b> The original implementation, and the one every seller's edit
 *         goes through - the vendor catalogue screen links here rather than duplicating a
 *         form. Also the resubmission path for a rejected listing.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code MarketplaceCatalogAdminService.updateMarketplaceDetails} -
 *         PUT /api/marketplace/admin/products/&#123;id&#125;/marketplace-details</td>
 *     <td>brand, unitOfMeasure</td>
 *     <td><b>Re-triggers</b> since M6; did not before. Latent rather than live at the time
 *         (only the un-moderated platform owner could reach the route), which is why it was
 *         missed - and no longer latent, see the row below. Category, minimum order quantity
 *         and slug are written here too and are exempt - the reasons are on the method.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code VendorCatalogueController.updateMarketplaceDetails} -
 *         PUT /api/vendor/catalogue/products/&#123;id&#125;/marketplace-details</td>
 *     <td>brand, unitOfMeasure</td>
 *     <td><b>Re-triggers</b>, by delegation - M8. This is the route the row above called
 *         "the obvious next thing a vendor asks for", and it is the reason the M6 fix was
 *         made against a latent hole: a vendor IS moderated, so from here the approve-then-
 *         swap is live rather than theoretical. The controller adds no moderation logic of
 *         its own - it proves the caller may sell, scopes the row to them, and calls the
 *         same service method, inheriting the re-trigger. It drops {@code slug} on the way
 *         (see {@code UpdateVendorMarketplaceDetailsRequest}); category and minimum order
 *         quantity pass through and keep their exemptions.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code MarketplaceCatalogAdminService.setListing} / {@code bulkSetListing}, and
 *         the vendor-facing copies at /api/vendor/catalogue/**</td>
 *     <td>none (marketplaceListed, and a slug derived from the name)</td>
 *     <td><b>Exempt.</b> Toggling "sell this" does not change what it is, and the public
 *         catalogue predicate independently requires APPROVED, so a PENDING product cannot
 *         reach a buyer by being listed. These two methods add no identity write of their
 *         own; the vendor controller's OTHER route does, and has its own row above.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code ProductManagementService.create} and {@code bulkUpload}</td>
 *     <td>all of them, at creation</td>
 *     <td><b>Exempt by construction</b> - there is no approval to invalidate. Both stamp
 *         {@link #initialStatusFor} instead, which is PENDING for a vendor. Bulk upload
 *         matters most here: it is how a large catalogue arrives, and leaving it on the
 *         column default is the one way to get several hundred unmoderated listings in at
 *         once.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code StockManagementService} stock-in / stock-out / adjust</td>
 *     <td>none (quantityOnHand)</td>
 *     <td><b>Exempt</b> by the price-and-stock ruling above - the ruling this class exists
 *         to state.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code ProductManagementService.deactivate}</td>
 *     <td>none (active)</td>
 *     <td><b>Exempt.</b> Withdrawing a product is not a claim about it, and a re-review on
 *         the way out would achieve nothing.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code CompanyVendorLinkService} supplier back-fill</td>
 *     <td>none (companyVendor)</td>
 *     <td><b>Exempt.</b> Who a buying company sources from is private bookkeeping, invisible
 *         on the storefront.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code IncomingStockService} - the buyer's copy of a received item</td>
 *     <td>all of them, at creation, in the BUYER's tenant</td>
 *     <td><b>Exempt</b> because the row is not a listing: it is a buying company's private
 *         inventory, {@code marketplaceListed} is hard-coded false, and vendors cannot buy.
 *         Note it leaves {@code approval_status} at the column default rather than calling
 *         {@link #initialStatusFor} - harmless, since the column is never read for a
 *         non-seller (see the class javadoc above), but it is the one row in this table
 *         relying on that guarantee rather than restating it.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code ProductModerationService.approve} / {@code reject}</td>
 *     <td>none (the decision columns themselves)</td>
 *     <td><b>Exempt, necessarily</b> - this is the reviewer, not the seller.</td>
 *   </tr>
 * </table>
 */
public final class ProductModerationRules {

    private ProductModerationRules() {
    }

    /**
     * Whether this product is the kind of row moderation has any opinion about: one
     * owned by a party that may sell, other than the platform owner.
     *
     * <p>Note it does NOT require {@code marketplaceListed}. A vendor's unlisted draft
     * belongs in the queue - reviewing it before they list it is the whole point, and
     * requiring the flag first would mean every listing went live for the instant
     * between the flip and the review.
     */
    public static boolean isModerated(Product product, Client owner) {
        return product != null && owner != null && owner.canSell() && !owner.isPlatformOwner();
    }

    /**
     * The status a newly created product starts at.
     *
     * <p>APPROVED for the platform owner, PENDING for a vendor. For an ordinary buying
     * company it is PENDING too, which is meaningless for them and never read - see the
     * class javadoc. Returned explicitly rather than left to the column default so the
     * platform owner's case is a stated rule rather than an omission.
     */
    public static ProductApprovalStatus initialStatusFor(Client owner) {
        return owner != null && owner.isPlatformOwner()
                ? ProductApprovalStatus.APPROVED
                : ProductApprovalStatus.PENDING;
    }

    /**
     * Whether a change to these fields invalidates an existing approval.
     *
     * <p>Takes the before/after of the identity fields only. Price, cost, stock levels
     * and the low-stock threshold are deliberately not parameters: they cannot reach
     * this decision, so there is no way for a later edit to accidentally start feeding
     * them in.
     */
    public static boolean invalidatesApproval(
            String oldName,
            String newName,
            String oldSku,
            String newSku,
            String oldDescription,
            String newDescription,
            String oldBrand,
            String newBrand,
            String oldImageUrl,
            String newImageUrl,
            String oldUnitOfMeasure,
            String newUnitOfMeasure) {
        return changed(oldName, newName)
                || changed(oldSku, newSku)
                || changed(oldDescription, newDescription)
                || changed(oldBrand, newBrand)
                || changed(oldImageUrl, newImageUrl)
                || changed(oldUnitOfMeasure, newUnitOfMeasure);
    }

    private static boolean changed(String before, String after) {
        return before == null ? after != null : !before.equals(after);
    }
}
