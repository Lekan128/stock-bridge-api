package com.procurepal_services.stock_bridge_api.product.bulk;

/**
 * The three vendor columns of one exported product row, flattened so the export writer never
 * touches a lazy JPA association.
 *
 * <h2>Why the export carries vendor columns at all</h2>
 * The export and the template are the same column set on purpose - a tenant's most common
 * workflow with these two files is "export what we have, edit it, upload it back", and a column
 * that exists on the template but not the export is a column that silently empties itself on
 * every round trip. Since {@code vendor_name} now updates the product's supplier line on import,
 * an export missing it would mean re-uploading your own catalog wipes its vendor attribution.
 *
 * @param vendorName the preferred supplier's name, or null when the product has no supplier line.
 * @param vendorSku that supplier's own code for the product, if recorded.
 * @param preferred always true in practice, since only the preferred line is exported; carried
 *     explicitly so the exported cell says {@code TRUE} rather than the writer assuming it.
 */
public record ProductVendorSnapshot(String vendorName, String vendorSku, boolean preferred) {
}
