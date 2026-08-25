package com.procurepal_services.stock_bridge_api.product.dto;

import com.procurepal_services.stock_bridge_api.entity.CompanyVendor;
import com.procurepal_services.stock_bridge_api.entity.CompanyVendorKind;
import com.procurepal_services.stock_bridge_api.entity.Product;
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
        // Which supplier this item comes from, as an entry in THIS company's own vendor
        // directory. Set automatically to the VERIFIED entry for the seller when goods arrive
        // from a marketplace order, and settable by hand to an EXTERNAL entry for stock sourced
        // off-platform. Null on most rows, permanently - a product with no supplier attached is
        // an ordinary product, not an incomplete one.
        UUID companyVendorId,
        // Denormalised onto the response so a product list can show "from Ada Millers" without a
        // second request per row. Read-only: the directory row is the authority, and the only way
        // to change what it says is through /api/company-vendors.
        String companyVendorName,
        CompanyVendorKind companyVendorKind,
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
        boolean active,
        boolean isLowStock,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<String> warnings) {

    public static ProductResponse from(Product product) {
        return from(product, null);
    }

    public static ProductResponse from(Product product, List<String> warnings) {
        boolean lowStock = product.getLowStockThreshold() != null
                && product.getQuantityOnHand() <= product.getLowStockThreshold();
        // Same tenant as the product by construction, so navigating the lazy association is
        // safe here - unlike sourceProductId, which points across tenants and stays a raw UUID.
        CompanyVendor vendor = product.getCompanyVendor();
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
                vendor == null ? null : vendor.getId(),
                vendor == null ? null : vendor.getName(),
                vendor == null ? null : vendor.getVendorKind(),
                product.getLowStockThreshold(),
                product.getBrand(),
                product.getUnitOfMeasure(),
                product.getPackagingUnit(),
                product.getPackagingSize(),
                product.getImageUrl(),
                product.isActive(),
                lowStock,
                product.getCreatedAt(),
                product.getUpdatedAt(),
                warnings);
    }
}
