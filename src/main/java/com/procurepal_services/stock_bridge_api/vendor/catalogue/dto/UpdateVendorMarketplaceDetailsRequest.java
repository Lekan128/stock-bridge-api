package com.procurepal_services.stock_bridge_api.vendor.catalogue.dto;

import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.UpdateMarketplaceDetailsRequest;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * The marketplace facets a SELLER may set on their own product: brand, where it is filed, and
 * the minimum a buyer must order.
 *
 * <h2>unitOfMeasure used to be here too</h2>
 * It was the whole reason this route first existed - a B2B price is meaningless without
 * knowing whether it is per 50kg bag, per carton or per litre - but it has since moved onto
 * the same request as everything else a seller sets on a product, {@code /api/products}
 * create/update, alongside the newer {@code packagingUnit}/{@code packagingSize} pair (e.g.
 * unit "KG" + packaging "BAG" + size 50 = "a 50kg bag"). That is also where it is validated
 * against the fixed {@code product.unit.UnitOfMeasure} catalog now. This route did not lose
 * any capability by the move: a vendor still sets their unit exactly as before, just through
 * the product form instead of this one, and on the SAME request as name/price/SKU rather than
 * a second trip. What is left here - brand - stays because it has nowhere more natural to
 * live: it is a marketplace-only facet with no equivalent field on an ordinary buying
 * company's product, unlike unitOfMeasure/packagingUnit/packagingSize which every tenant now
 * benefits from recording.
 *
 * <h2>Why this is a separate record from {@link UpdateMarketplaceDetailsRequest}</h2>
 * It is the operator's record minus one field, and the missing field is the point. A
 * vendor may not author a {@code slug}. Expressing that by accepting the operator's DTO
 * and quietly dropping the value would be worse than either alternative: the caller sends
 * a slug, gets a 200, and finds their URL unchanged with nothing to explain it. A record
 * without the component says the same thing in the type - there is no field to send, the
 * OpenAPI shape does not offer one, and the mapping below is where a reader finds out why.
 *
 * <p>The slug ruling is not this module's to reverse.
 * {@code MarketplaceCatalogService.productByIdOrSlug} records that {@code products.slug}
 * is unique PER TENANT (V6's partial index is on {@code (client_id, slug)}) while the
 * storefront resolves {@code /product/:idOrSlug} across every active seller - so two
 * sellers may legitimately hold the same slug and the lookup returns whichever the
 * ordering puts first. Today that is a curiosity, because only ProcurePal authors slugs
 * and everything else is derived from a name. Handing the field to vendors turns it into
 * a way to aim at someone else's URL: a vendor who types {@code dangote-cement} takes a
 * share of a link they did not earn, and neither party can tell which row a buyer will
 * get. That comment names the fix - make slugs globally unique, which is a schema change
 * plus a migration for existing rows - and says it "belongs to whoever adds vendor-authored
 * slugs". Vendor-authored slugs are therefore not added here. A vendor's slug is still
 * derived from their product name on first listing, which is the behaviour they actually
 * need, and their listings still have working storefront URLs.
 *
 * <h2>The fields that ARE here, and why each is the seller's business</h2>
 * <ul>
 *   <li><b>brand</b> - only the seller knows it, it is an identity field, so setting it
 *       sends the listing back for review - see {@code ProductModerationRules}.</li>
 *   <li><b>categoryId</b> / <b>clearCategory</b> - filing. The TAXONOMY is the operator's
 *       and stays read-only to a vendor (there is no vendor route that creates, renames or
 *       deletes a category); choosing which existing shelf a product sits on is the
 *       seller's merchandising decision, and the alternative is every vendor's catalogue
 *       arriving uncategorised and invisible to the storefront's category filter. Nothing
 *       cross-tenant is reachable through it: the id is resolved against the global
 *       category table and the PRODUCT is still pinned to the caller by {@code ownedBy}.</li>
 *   <li><b>minOrderQuantity</b> - a commercial term on the same footing as price. "Sold in
 *       pallets of 20" is a fact about how the seller trades, it moves with their stock
 *       position, and checkout revalidates it, so no buyer is ever committed to a quantity
 *       they did not see.</li>
 * </ul>
 *
 * <p>Every field is nullable and PATCH-like even though the verb is PUT, matching the
 * operator's record and {@code UpdateProductRequest}'s convention. Same asymmetry too:
 * {@code categoryId} null means "leave as is", not "uncategorise" - that is
 * {@code clearCategory}, which exists because one nullable field cannot express both.
 */
public record UpdateVendorMarketplaceDetailsRequest(
        UUID categoryId,
        Boolean clearCategory,
        @Min(1) Integer minOrderQuantity,
        @Size(max = 120) String brand) {

    /**
     * Widens this into the record the shared service takes, with {@code slug} pinned to
     * null - "leave the slug alone" in that record's patch-style contract - and
     * {@code unitOfMeasure} likewise always null, since this route no longer carries it at
     * all: a vendor sets it through {@code /api/products} now.
     *
     * <p>Deliberately a mapping rather than a second service method. The service is the one
     * place that decides what a brand change costs a seller, and a vendor-specific copy of
     * it is how the two surfaces would eventually disagree about that.
     */
    public UpdateMarketplaceDetailsRequest toCatalogRequest() {
        return new UpdateMarketplaceDetailsRequest(categoryId, clearCategory, minOrderQuantity, brand, null);
    }
}
