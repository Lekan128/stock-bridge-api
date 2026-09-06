package com.procurepal_services.stock_bridge_api.product.dto;

import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOption;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOptions;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ProductResponse(
        UUID id,
        String name,
        String sku,
        String description,
        BigDecimal unitPrice,
        BigDecimal costPrice,
        int quantityOnHand,
        // Bought from the marketplace and paid for, but not yet confirmed as received - so it is
        // NOT part of quantityOnHand and must never be presented as usable stock. It becomes
        // on-hand only when the buyer marks the order received, which writes a real IN movement.
        int incomingQuantity,
        // The ProcurePal catalog product this row was created from, when it was created by a
        // marketplace purchase rather than by hand. Null for anything the tenant added themselves.
        UUID sourceProductId,
        // V19: replaces companyVendorId/companyVendorName/companyVendorKind, which assumed a
        // product has at most one supplier - the bottleneck this whole feature removes. A
        // product may now have any number of ProductVendor lines (see that entity and
        // MULTI_VENDOR_INVENTORY_DESIGN.md section 5.1); this is only the NAME of whichever one
        // is currently preferred (Product.getPreferredVendor()), denormalised onto the response
        // the same way the old companyVendorName was, so a product list can show "from Ada
        // Millers" without a second request per row. Null when the product has no vendors at
        // all. The full vendor list, with cost/packaging/quantity per line, lives at
        // GET /api/products/{id}/vendors - this field is a display convenience, not a
        // replacement for that endpoint.
        String preferredVendorName,
        Integer lowStockThreshold,
        // brand is READ-ONLY here: /api/products has never written it and still does not - a
        // seller sets it through the marketplace-details route (their own at
        // /api/vendor/catalogue/**, ProcurePal's at /api/marketplace/admin/**), which is
        // where the moderation re-trigger for it lives. It is reported here because the
        // product FORM is one screen: a vendor editing a listing has to see the brand they
        // already have before deciding whether to change it, and the alternative was a
        // second fetch of the catalogue page to render one text box. Null on most rows and
        // permanently so for a buying company's private stock, which has no brand at all.
        //
        // unitOfMeasure, packagingUnit and packagingSize are WRITABLE here (create/update all
        // apply them) - unlike brand, they moved onto this same request rather than staying on
        // the marketplace-details route, open to a buying company's own stock exactly as much
        // as a seller's listing. unitOfMeasure is what the product is measured in;
        // packagingUnit/packagingSize say how it is packaged and how much one package holds -
        // e.g. unitOfMeasure=KG, packagingUnit=BAG, packagingSize=50 is "a 50kg bag". See
        // ProductManagementService and UnitOfMeasure/UnitOfMeasureRole.
        String brand,
        String unitOfMeasure,
        String packagingUnit,
        BigDecimal packagingSize,
        String imageUrl,
        // V21 / UNIT_UX_CONTRACT.md section 2.3: the closed list of units a quantity for THIS
        // product may be entered in - its stock unit, its own pack, and the same-category base
        // units that have a static conversion factor. Steps 1, 2 and 4 of the contract's
        // algorithm; no supplier pack, which is what ProductVendorResponse.unitOptions adds once
        // a supplier has been chosen.
        //
        // Every quantity entry point draws its options from here and from nothing else - the
        // stock-in and stock-out toggles, the import review grid's "counted in" cell, both
        // spreadsheets' validation. That is contract non-negotiable 1 ("no quantity field
        // anywhere offers a unit with no conversion factor") made structural rather than
        // remembered: the old modals each built their own ~30-code list, the service accepted
        // two of them, and picking any of the other twenty-eight was a guaranteed 400
        // (UNIT_UX_REMEDIATION_PLAN.md section 3, P1-1). There is now one list and the server
        // owns it.
        //
        // Never null and never empty: a product with no unitOfMeasure at all - the pre-V17 row
        // that never got one - still gets a single-entry set labelled "units", because a form
        // has to render something and "nothing to count in" is not a state a picker can show.
        // Computed from fields already on the entity, so a page of products costs no extra
        // query. See UnitOptions for the algorithm, in its one place.
        List<UnitOption> unitOptions,
        boolean active,
        boolean isLowStock,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<String> warnings,
        // True when this product's own pack (above) is one of more than one distinct
        // (container, size) shape in play for it - its own plus every one of its vendors'
        // `ProductVendorPack` rows (MULTI_PACK_PER_VENDOR_DESIGN.md section 4.1). A product's
        // own pack stays deliberately singular by design (that design's section 3), but a reader
        // of THIS field alone has no way to tell "the only pack" from "the default among several,
        // the rest living on the Vendors tab" - which is exactly the ambiguity a product page
        // surfaced (the Apple/Carrot review, 2026-09-06). False, never null, for a product with
        // no pack of its own: the field the caller would annotate with it does not render either.
        boolean hasMultiplePacks) {

    /**
     * The no-vendor-info overload. Deliberately does NOT touch {@code product.getVendors()}/
     * {@code getPreferredVendor()} - that association is LAZY (see {@code Product.vendors}'
     * javadoc), and calling it here unconditionally would turn every list-of-products response
     * into an N+1 the moment anyone iterates a page and maps each row through this method. Use
     * {@link #from(Product, String, List)} when the caller has already resolved the preferred
     * vendor's name some other way (a targeted or batched query - see
     * {@code ProductManagementService.list}/{@code .get} for the two patterns).
     *
     * <p>{@code hasMultiplePacks} is always {@code false} through every overload below except
     * {@link #from(Product, String, List, boolean)} for the same N+1 reason: it takes a query
     * against {@code product_vendor_packs} to know, so only a caller that has already run
     * {@code ProductManagementService}'s batched or single-product check may report it truthfully.
     * A product fresh off a create/update, or reported back from a stock mutation, cannot yet
     * have more than one vendor pack in any real workflow, so {@code false} here is accurate, not
     * merely a placeholder.
     */
    public static ProductResponse from(Product product) {
        return from(product, null, null);
    }

    public static ProductResponse from(Product product, List<String> warnings) {
        return from(product, null, warnings);
    }

    public static ProductResponse from(Product product, String preferredVendorName, List<String> warnings) {
        return from(product, preferredVendorName, warnings, false);
    }

    public static ProductResponse from(
            Product product, String preferredVendorName, List<String> warnings, boolean hasMultiplePacks) {
        boolean lowStock = product.getLowStockThreshold() != null
                && product.getQuantityOnHand() <= product.getLowStockThreshold();
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getSku(),
                product.getDescription(),
                product.getUnitPrice(),
                product.getCostPrice(),
                product.getQuantityOnHand(),
                product.getIncomingQuantity(),
                product.getSourceProductId(),
                preferredVendorName,
                product.getLowStockThreshold(),
                product.getBrand(),
                product.getUnitOfMeasure(),
                product.getPackagingUnit(),
                product.getPackagingSize(),
                product.getImageUrl(),
                UnitOptions.forProduct(product),
                product.isActive(),
                lowStock,
                product.getCreatedAt(),
                product.getUpdatedAt(),
                warnings,
                hasMultiplePacks);
    }
}
