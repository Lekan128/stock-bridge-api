package com.procurepal_services.stock_bridge_api.imports;

import com.procurepal_services.stock_bridge_api.entity.ImportKind;
import com.procurepal_services.stock_bridge_api.imports.io.HeaderNames;
import com.procurepal_services.stock_bridge_api.imports.io.SheetTable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Header to field key, guessed - BULK_IMPORT_DESIGN.md section 6.2.
 *
 * <h2>The point of this class is the screen it prevents</h2>
 * Odoo shows a column-mapping step unconditionally; NetSuite makes it step three of five. Both
 * are wrong for the population that matters most here, which is someone who downloaded our own
 * template, filled it in, and uploaded it back: for them every header already *is* a field key,
 * and a mapping screen is a page of dropdowns that all already say the right thing. Making the
 * step conditional costs one {@code if} and removes an entire step from the common case.
 *
 * <p>So: map, then ask only if a required field is still unmapped. Nothing else forces the
 * screen - an unmapped optional column is reported as an {@code unmappedHeader} and quietly
 * ignored, because a supplier's price list carrying a "Notes" column we do not understand is not
 * a problem the user should have to click through.
 *
 * <h2>Three rounds, cheapest first</h2>
 * <ol>
 *   <li><b>Exact.</b> {@code HeaderNames.normalize} has already folded case, whitespace, dashes
 *       and the invisible characters Excel litters exports with, so "Unit Price", "unit price"
 *       and "UNIT-PRICE" all arrive as {@code unit_price} and match by equality. This round
 *       alone handles our own template and most competent exports.</li>
 *   <li><b>Alias.</b> A small hand-written table of what other systems call these things -
 *       "qty", "supplier", "reorder level", "waybill no". Small on purpose: every entry is a
 *       guess that could be wrong, and a wrong guess is worse than an unmapped column, because
 *       the user has to notice it to fix it. Entries earn their place by being unambiguous
 *       within their kind.</li>
 *   <li><b>Nothing.</b> Left unmapped, reported, and - if required - the mapping screen.</li>
 * </ol>
 *
 * <h2>Why the alias table is per-kind</h2>
 * {@code "unit"} means {@code stock_unit} on a catalog sheet and {@code counted_in} on a stock-in
 * sheet; {@code "cost"} means {@code cost_price} on one and {@code unit_cost} on the other; a
 * bare {@code "quantity"} means the opening balance on one and the delivered amount on the
 * other. A single flat table would have to pick a winner and would then be silently wrong for
 * half of all uploads.
 *
 * <p>Saved mappings keyed by header signature (NetSuite's feature) are design 6.2's phase 3 and
 * are not built here.
 */
@Component
public class ImportColumnMapper {

    private static final Map<String, String> CATALOG_ALIASES = catalogAliases();
    private static final Map<String, String> STOCK_IN_ALIASES = stockInAliases();

    /**
     * @param columnMapping normalized header to field key, only for headers that resolved. The
     *     wire form (contract section 4) is header to {@code field|null}; nulls are added back by
     *     the response assembler so the mapping screen can show every column including the ones
     *     it could not place.
     * @param unmappedHeaders headers, in file order, that resolved to nothing.
     * @param requiredFieldsMissing required field keys no header resolved to. Non-empty is the
     *     only thing that forces the mapping step.
     */
    public record Mapping(
            Map<String, String> columnMapping, List<String> unmappedHeaders, List<String> requiredFieldsMissing) {

        public Mapping {
            columnMapping = Map.copyOf(columnMapping);
            unmappedHeaders = List.copyOf(unmappedHeaders);
            requiredFieldsMissing = List.copyOf(requiredFieldsMissing);
        }

        public boolean needsMapping() {
            return !requiredFieldsMissing.isEmpty();
        }
    }

    public Mapping autoMap(SheetTable table, ImportKind kind, List<ImportFieldDescriptor> fields) {
        Map<String, String> aliases = kind == ImportKind.STOCK_IN ? STOCK_IN_ALIASES : CATALOG_ALIASES;
        java.util.Set<String> known = fields.stream().map(ImportFieldDescriptor::key).collect(java.util.stream.Collectors.toSet());

        Map<String, String> mapping = new LinkedHashMap<>();
        List<String> unmapped = new java.util.ArrayList<>();
        java.util.Set<String> claimed = new java.util.LinkedHashSet<>();

        for (String rawHeader : table.headers()) {
            String header = HeaderNames.normalize(rawHeader);
            if (header == null) {
                continue;
            }
            String field = null;
            if (known.contains(header)) {
                field = header;
            } else {
                String alias = aliases.get(header);
                if (alias != null && known.contains(alias)) {
                    field = alias;
                }
            }
            // First header wins a field. Two columns claiming `sku` is a file problem, but
            // silently letting the second overwrite the first would mean reading the wrong
            // column with no indication that anything happened; reporting the loser as unmapped
            // at least puts it on the mapping screen where it can be re-pointed.
            if (field != null && claimed.add(field)) {
                mapping.put(header, field);
            } else {
                unmapped.add(header);
            }
        }

        List<String> missing = fields.stream()
                .filter(ImportFieldDescriptor::required)
                .map(ImportFieldDescriptor::key)
                .filter(key -> !claimed.contains(key))
                .toList();

        return new Mapping(mapping, unmapped, missing);
    }

    /**
     * Re-derives which required fields are still missing after the user has hand-edited the
     * mapping, so {@code PATCH /mapping} answers with a session whose {@code needsMapping} is
     * true again if they mapped the wrong thing rather than pretending it is settled.
     */
    public List<String> missingRequiredFields(Map<String, String> columnMapping, List<ImportFieldDescriptor> fields) {
        java.util.Set<String> claimed = new java.util.HashSet<>(columnMapping.values());
        return fields.stream()
                .filter(ImportFieldDescriptor::required)
                .map(ImportFieldDescriptor::key)
                .filter(key -> !claimed.contains(key))
                .toList();
    }

    private static Map<String, String> catalogAliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        put(aliases, ImportFields.NAME, "product", "product_name", "item", "item_name", "title", "description_of_item");
        put(aliases, ImportFields.SKU, "code", "item_code", "product_code", "sku_code", "stock_code", "part_number", "barcode", "item_no");
        put(aliases, ImportFields.DESCRIPTION, "desc", "details", "notes", "remarks");
        put(aliases, ImportFields.UNIT_PRICE, "price", "selling_price", "sale_price", "sales_price", "list_price", "rrp");
        put(aliases, ImportFields.COST_PRICE, "cost", "buying_price", "purchase_price", "cost_per_unit", "unit_cost", "buy_price");
        // Each of the four renamed columns leads with the spelling OUR OWN template used before
        // UNIT_UX_CONTRACT.md section 9.4 - "quantity_on_hand" (section 5.1), then section 9.4's
        // "low_stock_threshold", "unit_of_measure" and "packaging_unit"/"packaging_size". Section
        // 9.4 makes them permanent read aliases rather than deprecations, so a sheet somebody
        // downloaded before the rename still maps by itself and never reaches the mapping screen.
        //
        // An aliased opening_stock is read under section 9.1's rule like any other - packs when
        // the row declares one. That is deliberate and it is stated in the contract: nothing has
        // reached production, so there is no saved sheet whose bare number needs its old meaning
        // preserved, and preserving it was the only argument for the deleted
        // opening_stock_counted_in column.
        put(aliases, ImportFields.OPENING_STOCK, "quantity_on_hand", "qty", "quantity", "stock", "on_hand", "opening_balance", "current_stock", "stock_on_hand", "qty_on_hand");
        put(aliases, ImportFields.LOW_STOCK_ALERT_AT, "low_stock_threshold", "reorder_level", "reorder_point", "min_stock", "minimum_stock", "low_stock", "reorder", "alert_at");
        // "unit" stays here and still means the STOCK unit on a catalog sheet - see the per-kind
        // note in this class's javadoc for why that is not the same answer as on a stock-in sheet.
        put(aliases, ImportFields.STOCK_UNIT, "unit_of_measure", "uom", "unit", "units", "measure", "base_unit", "unit_measure", "sold_in");
        put(aliases, ImportFields.PACK, "packaging_unit", "packaging", "pack_type", "package", "package_unit", "pack_unit");
        put(aliases, ImportFields.UNITS_PER_PACK, "packaging_size", "pack_size", "package_size", "size", "qty_per_pack");
        put(aliases, ImportFields.VENDOR_NAME, "supplier", "vendor", "supplier_name", "bought_from", "source", "distributor");
        put(aliases, ImportFields.VENDOR_SKU, "supplier_code", "vendor_code", "supplier_sku", "supplier_item_code", "their_code");
        put(aliases, ImportFields.IS_PREFERRED_VENDOR, "preferred", "main_supplier", "preferred_supplier", "primary_supplier", "default_supplier");
        return Map.copyOf(aliases);
    }

    private static Map<String, String> stockInAliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        put(aliases, ImportFields.SKU, "code", "item_code", "product_code", "sku_code", "stock_code", "part_number", "barcode", "item_no");
        put(aliases, ImportFields.PRODUCT_NAME, "product", "item", "item_name", "name", "description");
        put(aliases, ImportFields.VENDOR_NAME, "supplier", "vendor", "supplier_name", "bought_from", "source", "distributor");
        put(aliases, ImportFields.QUANTITY, "qty", "quantity_received", "received", "amount", "qty_received", "delivered");
        // "unit" and "unit_cost" lead for the same reason "quantity_on_hand" does above: they
        // are what every stock-in template published before UNIT_UX_CONTRACT.md section 5.2
        // called these columns, and section 5.2 keeps them accepted on read forever.
        put(aliases, ImportFields.COUNTED_IN, "unit", "uom", "units", "measure", "unit_of_measure");
        put(aliases, ImportFields.COST_PER_UNIT, "unit_cost", "cost", "cost_price", "price", "unit_price", "purchase_price", "buying_price");
        // Removed from the sheet by section 5.2, still mapped on purpose. A number here is read,
        // ignored, and warned about once per affected row (StockInRowHandler) - which is only
        // possible if the column resolves to a field at all. Left unmapped it would instead be
        // reported as a column we did not understand, which is both untrue and silent about the
        // thing the user needs to hear: that the pack now comes from their product setup.
        //
        // The key itself is now "units_per_pack" (section 9.4), so "packaging_size" - the header
        // the sheet actually carried before section 5.2 removed the column - has to be listed as
        // an alias here rather than matching by identity as it used to.
        put(aliases, ImportFields.UNITS_PER_PACK, "packaging_size", "pack_size", "package_size", "size", "qty_per_pack");
        put(aliases, ImportFields.RECEIVED_DATE, "date", "received", "delivery_date", "date_received", "invoice_date", "receipt_date");
        put(aliases, ImportFields.WAYBILL_OR_INVOICE_NO,
                "reference", "invoice", "invoice_no", "invoice_number", "waybill", "waybill_no", "reference_no", "ref", "doc_no");
        return Map.copyOf(aliases);
    }

    private static void put(Map<String, String> target, String field, String... aliases) {
        for (String alias : aliases) {
            // putIfAbsent, so an alias claimed by an earlier field stays claimed. "received"
            // means the date on a stock-in sheet, not the quantity, and the order above says so.
            target.putIfAbsent(alias, field);
        }
    }
}
