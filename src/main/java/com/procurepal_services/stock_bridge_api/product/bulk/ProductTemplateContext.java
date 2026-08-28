package com.procurepal_services.stock_bridge_api.product.bulk;

import com.procurepal_services.stock_bridge_api.imports.io.ImportLimits;
import java.util.List;

/**
 * Everything about ONE tenant that changes the product template it downloads. Passed in by
 * {@code ProductManagementService}, which owns the tenant and security lookups - keeping
 * {@link ProductExcelService} free of them is a rule its class javadoc has always stated, and the
 * template becoming per-tenant is exactly the change that would otherwise have broken it.
 *
 * @param isSeller whether this tenant's products are marketplace listings. The single column that
 *     has ever been tenant-conditional: a buying company has no selling price, so its template
 *     does not show {@code unit_price} at all rather than showing it as optional and inviting
 *     "should I fill this in?".
 * @param vendorNames the tenant's active suppliers, alphabetical, for the {@code vendor_name}
 *     dropdown. BULK_IMPORT_DESIGN.md section 5.2 calls this the bigger win of the two and the
 *     reason the template had to become per-tenant at all: a user who picks "Dangote Nigeria Plc"
 *     off a list is a user who never generates an unresolved-vendor row, never sees the value
 *     mapper, and never has to be asked a question about a supplier they already told us about.
 *     Above {@link ImportLimits#VENDOR_DROPDOWN_CAP} the list is dropped and the column is left as
 *     free text - see {@link #vendorDropdownNames()}.
 */
public record ProductTemplateContext(boolean isSeller, List<String> vendorNames) {

    public ProductTemplateContext {
        vendorNames = vendorNames == null ? List.of() : List.copyOf(vendorNames);
    }

    /** A tenant with no vendor directory yet, or a caller that has no reason to build one. */
    public static ProductTemplateContext of(boolean isSeller) {
        return new ProductTemplateContext(isSeller, List.of());
    }

    /**
     * The names that actually become a dropdown: none at all past the cap. Returning an empty list
     * rather than a truncated one is the deliberate part - an arbitrary first 200 of 900 vendors
     * would be a dropdown that is missing the vendor a given user needs, which is worse than no
     * dropdown because it reads as "your supplier is not in the system".
     */
    public List<String> vendorDropdownNames() {
        return vendorNames.size() > ImportLimits.VENDOR_DROPDOWN_CAP ? List.of() : vendorNames;
    }
}
