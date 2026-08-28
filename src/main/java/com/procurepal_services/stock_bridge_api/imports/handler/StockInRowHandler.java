package com.procurepal_services.stock_bridge_api.imports.handler;

import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.CompanyVendor;
import com.procurepal_services.stock_bridge_api.entity.ImportKind;
import com.procurepal_services.stock_bridge_api.entity.ImportSession;
import com.procurepal_services.stock_bridge_api.entity.MovementType;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.entity.StockMovement;
import com.procurepal_services.stock_bridge_api.imports.BatchContext;
import com.procurepal_services.stock_bridge_api.imports.CommitOutcome;
import com.procurepal_services.stock_bridge_api.imports.CommitPreview;
import com.procurepal_services.stock_bridge_api.imports.ImportBatchCache;
import com.procurepal_services.stock_bridge_api.imports.ImportCopy;
import com.procurepal_services.stock_bridge_api.imports.ImportFieldDescriptor;
import com.procurepal_services.stock_bridge_api.imports.ImportFields;
import com.procurepal_services.stock_bridge_api.imports.ImportRowHandler;
import com.procurepal_services.stock_bridge_api.imports.ImportRowState;
import com.procurepal_services.stock_bridge_api.imports.NameSimilarity;
import com.procurepal_services.stock_bridge_api.imports.RowContext;
import com.procurepal_services.stock_bridge_api.imports.RowValidation;
import com.procurepal_services.stock_bridge_api.imports.UndoOutcome;
import com.procurepal_services.stock_bridge_api.imports.UnresolvedValue;
import com.procurepal_services.stock_bridge_api.imports.ValueMappings;
import com.procurepal_services.stock_bridge_api.imports.ValueResolution;
import com.procurepal_services.stock_bridge_api.marketplace.SellerDirectory;
import com.procurepal_services.stock_bridge_api.marketplace.moderation.ProductModerationRules;
import com.procurepal_services.stock_bridge_api.product.bulk.StockInExcelService;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasure;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasureRole;
import com.procurepal_services.stock_bridge_api.repository.ImportSessionRowRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductVendorRepository;
import com.procurepal_services.stock_bridge_api.repository.StockMovementAllocationRepository;
import com.procurepal_services.stock_bridge_api.repository.StockMovementRepository;
import com.procurepal_services.stock_bridge_api.stock.StockManagementService;
import com.procurepal_services.stock_bridge_api.stock.dto.StockInRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The STOCK_IN row handler: "what arrived?"
 *
 * <p>An event, with a date, a supplier, a cost and an invoice number behind it (design 6.7).
 * Runs constantly, forever. We bring the rows - the template is a download of the tenant's own
 * catalog (design 8.1) - and the user brings one number per row.
 *
 * <h2>Why this handler is so much smaller than the catalog one</h2>
 * Design 8.2 is the reason, and it is worth restating: "commit is a loop over the existing
 * service, not new stock logic." Every valid row becomes a {@code StockInRequest} against
 * {@code StockManagementService.stockIn()}, which already handles unit conversion, vendor
 * resolution, {@code ProductVendor} creation for a new pairing, the cached rollups and the
 * weighted-average cost recalculation. The only additions are the batch transaction and the
 * {@code import_batch_id} stamp. Anything here that looked like inventory arithmetic would be a
 * second implementation of a thing that already works, and would drift from it.
 *
 * <h2>The blank-quantity rule, which is not an edge case</h2>
 * The pre-filled sheet contains the tenant's whole catalog, so a six-line delivery arrives as a
 * four-hundred-row file with three hundred and ninety-four empty quantity cells. Contract
 * section 8.11: a blank {@code quantity} is a silent skip, not an error. Not a warning either -
 * three hundred and ninety-four warnings is the same as none.
 *
 * <h2>Units, and what is deliberately not attempted</h2>
 * Design 8.3 rules out per-row dependent dropdowns in the spreadsheet: they are possible in
 * Excel via {@code INDIRECT} and they break in Google Sheets, Numbers and LibreOffice. The
 * answer is a flat dropdown of every unit code in the sheet, pre-filled per row with the right
 * one, and narrowing server-side to *that product's* two configured units - which is this
 * class's job, with the error naming the two that are valid. M2 left
 * {@link StockInExcelService#unitNotStockedMessage} public and static precisely so this handler
 * would use its wording rather than write a second one.
 */
@Component
@RequiredArgsConstructor
public class StockInRowHandler implements ImportRowHandler {

    private static final String CACHE_PRODUCTS_BY_SKU = "stock-in-products-by-sku";
    private static final String CACHE_VENDOR_LINE_COUNT = "stock-in-vendor-line-count";
    private static final String CACHE_ACTIVE_PRODUCTS = "stock-in-active-products";

    /** Beyond this a backdated delivery is warned about, never blocked - design 8.4. */
    private static final int BACKDATE_WARNING_DAYS = 365;

    private final ProductRepository productRepository;
    private final ProductVendorRepository productVendorRepository;
    private final StockMovementRepository stockMovementRepository;
    private final StockMovementAllocationRepository stockMovementAllocationRepository;
    private final ImportSessionRowRepository importSessionRowRepository;
    private final StockManagementService stockManagementService;
    private final SellerDirectory sellerDirectory;
    private final VendorDirectory vendorDirectory;

    @Override
    public ImportKind kind() {
        return ImportKind.STOCK_IN;
    }

    // ------------------------------------------------------------------ fields

    /**
     * Nine columns, of which the user fills one.
     *
     * <p>{@code sku} and {@code product_name} are {@code readOnly}: they identify the row, and
     * editing them in the grid would silently re-point a delivery at a different product.
     * {@code quantity} is the kind's single {@code primaryInput}, which is what lets the grid say
     * so visually - "we bring the rows, the user brings one number" is only true if the screen
     * makes that number obvious.
     */
    @Override
    public List<ImportFieldDescriptor> fields() {
        return List.of(
                new ImportFieldDescriptor(ImportFields.SKU, "Product code", ImportFieldDescriptor.Type.TEXT,
                        true, true, false,
                        "Your product code. This is how we match the row to your product - do not change it.", null),
                new ImportFieldDescriptor(ImportFields.PRODUCT_NAME, "Product", ImportFieldDescriptor.Type.TEXT,
                        false, true, false, "For your reference. We match on the code, not on this.", null),
                new ImportFieldDescriptor(ImportFields.VENDOR_NAME, "Supplier",
                        ImportFieldDescriptor.Type.REFERENCE, false, false, false,
                        "Who this delivery came from.", null),
                new ImportFieldDescriptor(ImportFields.QUANTITY, "Quantity", ImportFieldDescriptor.Type.INTEGER,
                        false, false, true,
                        "How much of this product arrived. Leave it empty for products you did not receive.", null),
                ImportFieldDescriptor.enumeration(ImportFields.UNIT, "Counted in", false,
                        "What the quantity is counted in - the product's own unit, or the pack you buy it by.",
                        RowValues.options(UnitOfMeasure.all())),
                ImportFieldDescriptor.of(ImportFields.UNIT_COST, "Cost each", ImportFieldDescriptor.Type.MONEY,
                        false, "What one of the above cost you."),
                ImportFieldDescriptor.of(ImportFields.PACKAGING_SIZE, "Units per pack",
                        ImportFieldDescriptor.Type.NUMBER, false,
                        "How many base units are in one pack, if the quantity is in packs."),
                ImportFieldDescriptor.of(ImportFields.RECEIVED_DATE, "Date received",
                        ImportFieldDescriptor.Type.DATE, false,
                        "When the delivery actually arrived. We use this to work out which stock was sold first."),
                ImportFieldDescriptor.text(ImportFields.REFERENCE, "Waybill or invoice",
                        "So you can find this delivery again."));
    }

    // ---------------------------------------------------------------- validate

    @Override
    public RowValidation validate(RowContext ctx) {
        RowValidation.Builder out = RowValidation.builder();

        String sku = ctx.text(ImportFields.SKU);
        out.value(ImportFields.SKU, sku);
        out.value(ImportFields.PRODUCT_NAME, ctx.text(ImportFields.PRODUCT_NAME));
        String subject = ImportCopy.subject(ctx.text(ImportFields.PRODUCT_NAME), sku);

        // Contract section 8.11. Checked before anything else, because a row with no quantity is
        // not a delivery and must not be told off for the state of its other columns - most of
        // which we pre-filled ourselves.
        Integer quantity = readQuantity(ctx, out, subject);
        if (quantity == null && !out.hasErrors()) {
            out.value(ImportFields.AUTO_SKIP, Boolean.TRUE);
            return out.build();
        }

        if (sku == null) {
            out.error(ImportFields.SKU, "SKU_REQUIRED",
                    "Row " + ctx.excelRow() + " records a delivery but does not say which product it was for.");
            return out.build();
        }

        Product product = resolveProduct(ctx, out, sku, subject);
        if (product == null) {
            // Either unknown and unanswered (an error was raised), or answered with
            // "create this product", which the commit will honour. Nothing further to check
            // against a product that does not exist yet - its units are whatever the resolution
            // said they would be.
            readRemainingColumns(ctx, out, subject, null);
            return out.build();
        }
        out.resolvedTo(product.getId(), product.getName());
        subject = product.getName();

        readRemainingColumns(ctx, out, subject, product);
        validateVendor(ctx, out, product, subject);
        return out.build();
    }

    /**
     * The one number the user brings.
     *
     * <p>Blank and zero both mean "nothing arrived" and both skip. Zero specifically, because a
     * pre-filled sheet invites someone to type {@code 0} against the things they did not get,
     * and telling them that is an error would be pedantry about a perfectly clear intention.
     * Negative is an error, because a delivery only ever adds stock and a negative number means
     * the user is trying to do something this screen cannot do.
     */
    private Integer readQuantity(RowContext ctx, RowValidation.Builder out, String subject) {
        String raw = ctx.text(ImportFields.QUANTITY);
        if (raw == null) {
            out.value(ImportFields.QUANTITY, null);
            return null;
        }
        Optional<BigDecimal> parsed = ctx.decimal(ImportFields.QUANTITY);
        if (parsed.isEmpty()) {
            out.error(ImportFields.QUANTITY, "NOT_A_NUMBER",
                    "We can't read %s as a quantity for %s.".formatted(ImportCopy.quote(raw), subject));
            out.value(ImportFields.QUANTITY, null);
            return null;
        }
        BigDecimal value = parsed.get();
        if (value.signum() == 0) {
            out.value(ImportFields.QUANTITY, null);
            return null;
        }
        if (value.signum() < 0) {
            out.error(ImportFields.QUANTITY, "NEGATIVE_QUANTITY",
                    "A delivery only ever adds stock, so %s cannot have arrived in a negative quantity. "
                            .formatted(subject)
                            + "Use a stock adjustment if you need to take stock off.");
            out.value(ImportFields.QUANTITY, null);
            return null;
        }
        if (value.stripTrailingZeros().scale() > 0) {
            out.error(ImportFields.QUANTITY, "NOT_A_WHOLE_NUMBER",
                    "We record whole units, so %s of %s is not something we can store."
                            .formatted(ImportCopy.quote(raw), subject));
            out.value(ImportFields.QUANTITY, null);
            return null;
        }
        if (value.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            out.error(ImportFields.QUANTITY, "NUMBER_TOO_LARGE",
                    "That is a larger delivery of %s than we can record.".formatted(subject));
            out.value(ImportFields.QUANTITY, null);
            return null;
        }
        out.value(ImportFields.QUANTITY, value.intValue());
        return value.intValue();
    }

    /**
     * Matches the row to a product, or turns the miss into a question.
     *
     * <p>An unmatched SKU is an ERROR here, unlike an unmatched supplier on a catalog row, and
     * the asymmetry is deliberate: a delivery with no supplier is a delivery we can still record,
     * while a delivery of nothing is not a thing. It is still collapsed into one resolution card
     * per distinct SKU by {@link #unresolvedValues}, where design 6.7's inline-create escape
     * hatch lives - "a supplier's truck arrives with eleven things you stock and one you have
     * never bought before" is the case that motivated the whole mechanism.
     */
    private Product resolveProduct(RowContext ctx, RowValidation.Builder out, String sku, String subject) {
        Product product = findBySku(ctx.tenantId(), ctx.cache(), sku);
        if (product != null) {
            return product;
        }
        Optional<ValueResolution> resolution = ctx.resolution(ImportFields.SKU);
        if (resolution.isPresent()) {
            ValueResolution answer = resolution.get();
            if (answer.isExisting()) {
                return productRepository.findByIdAndClientId(answer.id(), ctx.tenantId()).orElse(null);
            }
            if (answer.isCreateNew() || answer.isSkipRows()) {
                if (answer.isSkipRows()) {
                    out.value(ImportFields.AUTO_SKIP, Boolean.TRUE);
                }
                return null;
            }
        }
        out.error(ImportFields.SKU, "PRODUCT_NOT_FOUND",
                "You do not stock anything under this code yet. Answer the question above to match %s to a "
                        .formatted(ImportCopy.quote(subject))
                        + "product you already have, add it to your catalog, or leave this delivery out.");
        return null;
    }

    private void readRemainingColumns(
            RowContext ctx, RowValidation.Builder out, String subject, Product product) {
        validateUnit(ctx, out, product, subject);
        RowValues.money(ctx, out, ImportFields.UNIT_COST, "Cost each", subject);
        RowValues.decimal(ctx, out, ImportFields.PACKAGING_SIZE, "Units per pack", subject);
        validateReceivedDate(ctx, out, subject);
        out.value(ImportFields.REFERENCE, ctx.text(ImportFields.REFERENCE));
    }

    /**
     * Design 8.3's server-side narrowing: a unit code that exists in the catalog but is not one
     * of <em>this product's</em> two configured units.
     *
     * <p>The message is M2's {@link StockInExcelService#unitNotStockedMessage}, which was left
     * static and public for exactly this call. Writing a second version of "Rice 50kg is stocked
     * in KG or BAG" here would be two sentences to keep in step for no benefit.
     */
    private void validateUnit(RowContext ctx, RowValidation.Builder out, Product product, String subject) {
        String raw = ctx.text(ImportFields.UNIT);
        if (raw == null) {
            out.value(ImportFields.UNIT, null);
            return;
        }
        Optional<UnitOfMeasure> resolved = UnitOfMeasure.fromCodeOrLabel(raw);
        if (resolved.isEmpty()) {
            ImportFieldDescriptor.Option suggestion = RowValues.closestUnit(raw, UnitOfMeasureRole.BASE);
            out.issue(com.procurepal_services.stock_bridge_api.imports.RowIssue.error(
                    ImportFields.UNIT, "UNIT_NOT_RECOGNISED",
                    suggestion == null
                            ? "We don't recognise %s as a unit.".formatted(ImportCopy.quote(raw))
                            : "We don't recognise %s as a unit. Did you mean %s?"
                                    .formatted(ImportCopy.quote(raw), suggestion.label()),
                    suggestion));
            out.value(ImportFields.UNIT, null);
            return;
        }
        String code = resolved.get().code();
        if (product == null) {
            out.value(ImportFields.UNIT, code);
            return;
        }
        boolean isBase = code.equalsIgnoreCase(product.getUnitOfMeasure());
        boolean isPack = product.getPackagingUnit() != null && code.equalsIgnoreCase(product.getPackagingUnit());
        if (product.getUnitOfMeasure() == null || isBase || isPack) {
            out.value(ImportFields.UNIT, code);
            return;
        }
        ImportFieldDescriptor.Option suggestion =
                new ImportFieldDescriptor.Option(product.getUnitOfMeasure(),
                        ImportCopy.unitLabel(product.getUnitOfMeasure()));
        out.issue(com.procurepal_services.stock_bridge_api.imports.RowIssue.error(
                ImportFields.UNIT, "UNIT_NOT_STOCKED",
                StockInExcelService.unitNotStockedMessage(
                        subject,
                        ImportCopy.unitLabel(product.getUnitOfMeasure()),
                        product.getPackagingUnit() == null ? null : ImportCopy.unitLabel(product.getPackagingUnit())),
                suggestion));
        out.value(ImportFields.UNIT, null);
    }

    /**
     * Design 8.4: a future date is refused, a very old one is warned about but allowed.
     *
     * <p>Backdating is the normal case for this feature, not an edge case - "record what we
     * bought outside the platform" is mostly about last month - and {@code occurred_at} exists so
     * FIFO consumes those lots in the order they really arrived. A date in the future, though, is
     * always a typo, and it would place the lot after everything real and quietly corrupt the
     * draw order for as long as it stayed there. {@code chk_stock_movements_occurred_at_not_future}
     * would catch it in the database and roll back the entire import with a constraint name for a
     * message; catching it here costs one comparison and names the row.
     */
    private void validateReceivedDate(RowContext ctx, RowValidation.Builder out, String subject) {
        LocalDate received = RowValues.date(ctx, out, ImportFields.RECEIVED_DATE, "Date received", subject);
        if (received == null) {
            return;
        }
        LocalDate today = LocalDate.now();
        if (received.isAfter(today)) {
            out.error(ImportFields.RECEIVED_DATE, "DATE_IN_FUTURE",
                    "%s is dated %s, which has not happened yet.".formatted(subject, received));
            out.value(ImportFields.RECEIVED_DATE, null);
            return;
        }
        if (received.isBefore(today.minusDays(BACKDATE_WARNING_DAYS))) {
            out.warning(ImportFields.RECEIVED_DATE, "DATE_LONG_AGO",
                    "This delivery of %s is dated %s, over a year ago. That is fine if it is right - we will "
                            .formatted(subject, received)
                            + "treat it as older stock and sell it first.");
        }
    }

    /**
     * A product that already has supplier lines needs one named on the delivery.
     *
     * <p>{@code StockManagementService.stockIn} throws {@code CompanyVendorRequiredException} in
     * exactly this case, and once the commit is running that exception rolls back the whole
     * import. Asking the same question at validation time turns a whole-file failure into one
     * outlined cell.
     */
    private void validateVendor(RowContext ctx, RowValidation.Builder out, Product product, String subject) {
        String vendorName = ctx.text(ImportFields.VENDOR_NAME);
        out.value(ImportFields.VENDOR_NAME, vendorName);
        Optional<ValueResolution> resolution = ctx.resolution(ImportFields.VENDOR_NAME);
        boolean resolvable = vendorName != null
                && (resolution.map(ValueResolution::namesAnEntity).orElse(false)
                        || vendorDirectory.match(ctx.tenantId(), ctx.cache(), vendorName) != null);

        if (resolvable) {
            return;
        }
        boolean productHasVendors = vendorLineCount(ctx.tenantId(), ctx.cache(), product.getId()) > 0;
        if (vendorName == null && productHasVendors) {
            out.error(ImportFields.VENDOR_NAME, "VENDOR_REQUIRED",
                    "You buy %s from more than one place, so we need to know who this delivery came from - "
                            .formatted(subject)
                            + "otherwise we cannot tell whose stock was sold when you sell it.");
            return;
        }
        if (vendorName != null) {
            out.warning(ImportFields.VENDOR_NAME, "VENDOR_UNKNOWN",
                    "%s is not one of your suppliers yet. Answer the supplier question above and we will link "
                            .formatted(ImportCopy.quote(vendorName))
                            + "every delivery that names them.");
        }
    }

    // ----------------------------------------------------------- validateBatch

    /**
     * Nothing to do, and that is a decision rather than an omission.
     *
     * <p>The catalog's cross-row rules exist because a repeated SKU there is ambiguous - one
     * product described twice, or one product with two suppliers. Here it is not ambiguous at
     * all: two rows with the same SKU are two deliveries of the same thing, which is an
     * ordinary Tuesday. They may even have different dates, suppliers and costs, and each is its
     * own lot with its own place in the FIFO queue. Collapsing or erroring on them would destroy
     * information the multi-vendor design exists to keep.
     */
    @Override
    public void validateBatch(BatchContext ctx) {
        // Intentionally empty. See the javadoc - a repeated SKU is two deliveries, not a clash.
    }

    // -------------------------------------------------------- unresolvedValues

    /**
     * Unknown product codes and unknown supplier names, each collapsed to one question.
     *
     * <p>The product card is design 6.7's escape hatch: close matches by code and by name,
     * "create this product" collecting the minimum - a name and a base unit of measure, because
     * a product with no unit cannot be stocked into and the very row that asked for it would
     * fail again on the next pass - or "skip this row". Bounded by construction: a handful of
     * new items in a delivery, not a whole file, which is what keeps it from degrading into
     * design 5.1's rejected "fill in the form afterwards" experience.
     */
    @Override
    public List<UnresolvedValue> unresolvedValues(BatchContext ctx) {
        List<UnresolvedValue> unresolved = new ArrayList<>();
        boolean mayCreateProducts = ProductCatalogRowHandler.hasAuthority("MANAGE_PRODUCTS");
        boolean mayCreateVendors = ProductCatalogRowHandler.hasAuthority("MANAGE_VENDORS");

        Map<String, List<Integer>> unknownSkus = new LinkedHashMap<>();
        Map<String, String> skuSpelling = new LinkedHashMap<>();
        Map<String, List<Integer>> unknownVendors = new LinkedHashMap<>();
        Map<String, String> vendorSpelling = new LinkedHashMap<>();

        for (ImportRowState state : ctx.states()) {
            if (state.isSkipped()) {
                continue;
            }
            String sku = state.text(ImportFields.SKU);
            if (sku != null && findBySku(ctx.tenantId(), ctx.cache(), sku) == null) {
                String folded = ValueMappings.normalizeKey(sku);
                skuSpelling.putIfAbsent(folded, sku);
                unknownSkus.computeIfAbsent(folded, key -> new ArrayList<>()).add(state.excelRow());
            }
            String vendorName = state.text(ImportFields.VENDOR_NAME);
            if (vendorName != null && vendorDirectory.match(ctx.tenantId(), ctx.cache(), vendorName) == null) {
                String folded = ValueMappings.normalizeKey(vendorName);
                vendorSpelling.putIfAbsent(folded, vendorName);
                unknownVendors.computeIfAbsent(folded, key -> new ArrayList<>()).add(state.excelRow());
            }
        }

        for (Map.Entry<String, List<Integer>> entry : unknownSkus.entrySet()) {
            String value = skuSpelling.get(entry.getKey());
            unresolved.add(new UnresolvedValue(
                    ImportFields.SKU,
                    "Product",
                    value,
                    entry.getValue().size(),
                    entry.getValue().stream().limit(50).toList(),
                    UnresolvedValue.Kind.PRODUCT,
                    productSuggestions(ctx, value),
                    mayCreateProducts,
                    false,
                    true,
                    ctx.valueMappings().resolutionFor(ImportFields.SKU, value).orElse(null)));
        }
        for (Map.Entry<String, List<Integer>> entry : unknownVendors.entrySet()) {
            String value = vendorSpelling.get(entry.getKey());
            unresolved.add(new UnresolvedValue(
                    ImportFields.VENDOR_NAME,
                    "Supplier",
                    value,
                    entry.getValue().size(),
                    entry.getValue().stream().limit(50).toList(),
                    UnresolvedValue.Kind.VENDOR,
                    vendorDirectory.suggestionsFor(ctx.tenantId(), ctx.cache(), value),
                    mayCreateVendors,
                    true,
                    false,
                    ctx.valueMappings().resolutionFor(ImportFields.VENDOR_NAME, value).orElse(null)));
        }
        return unresolved;
    }

    /** Close matches by code and by name, per design 6.4 - a mistyped SKU is usually one character out. */
    private List<UnresolvedValue.Suggestion> productSuggestions(BatchContext ctx, String value) {
        return activeProducts(ctx.tenantId(), ctx.cache()).stream()
                .map(product -> new UnresolvedValue.Suggestion(
                        product.getId().toString(),
                        product.getName(),
                        product.getSku(),
                        Math.max(
                                NameSimilarity.score(value, product.getSku()),
                                NameSimilarity.score(value, product.getName()))))
                .filter(suggestion -> suggestion.score() >= NameSimilarity.SUGGESTION_FLOOR)
                .sorted(Comparator.comparingDouble(UnresolvedValue.Suggestion::score).reversed())
                .limit(3)
                .toList();
    }

    // ---------------------------------------------------------------- preview

    /**
     * Design 9.4's stock-in wording: "Record 18 deliveries - 1,240 kg across 18 products from 4
     * suppliers, dated 12 Jan - 3 Feb. Total cost ₦8,420,000."
     *
     * <p>Same screen as the catalog's, different sentences, and the button says what it does.
     */
    @Override
    public CommitPreview preview(BatchContext ctx) {
        int deliveries = 0;
        long quantity = 0;
        Set<String> units = new LinkedHashSet<>();
        Set<UUID> products = new LinkedHashSet<>();
        Set<String> suppliers = new LinkedHashSet<>();
        BigDecimal totalCost = BigDecimal.ZERO;
        LocalDate earliest = null;
        LocalDate latest = null;
        int skipped = 0;
        int productsToCreate = distinctCreateNewProducts(ctx).size();
        int vendorsToCreate = distinctCreateNewVendors(ctx).size();

        for (ImportRowState state : ctx.states()) {
            if (state.isSkipped()) {
                skipped++;
                continue;
            }
            if (!state.isCommittable()) {
                continue;
            }
            Object rawQuantity = state.value(ImportFields.QUANTITY);
            if (!(rawQuantity instanceof Number number) || number.intValue() <= 0) {
                continue;
            }
            deliveries++;
            quantity += number.intValue();
            units.add(state.text(ImportFields.UNIT) == null ? "unit" : state.text(ImportFields.UNIT));
            if (state.getResolvedEntityId() != null) {
                products.add(state.getResolvedEntityId());
            }
            if (state.text(ImportFields.VENDOR_NAME) != null) {
                suppliers.add(ValueMappings.normalizeKey(state.text(ImportFields.VENDOR_NAME)));
            }
            Object cost = state.value(ImportFields.UNIT_COST);
            if (cost != null) {
                totalCost = totalCost.add(
                        new BigDecimal(cost.toString()).multiply(BigDecimal.valueOf(number.intValue())));
            }
            String date = state.text(ImportFields.RECEIVED_DATE);
            if (date != null) {
                LocalDate parsed = LocalDate.parse(date);
                earliest = earliest == null || parsed.isBefore(earliest) ? parsed : earliest;
                latest = latest == null || parsed.isAfter(latest) ? parsed : latest;
            }
        }

        int productCount = Math.max(products.size(), productsToCreate);
        String unitPhrase = units.size() == 1
                ? ImportCopy.count(quantity) + " " + ImportCopy.unitSymbol(units.iterator().next())
                : ImportCopy.count(quantity) + " units";

        CommitPreview.Builder preview = CommitPreview.builder()
                .headline("Record %s from %s".formatted(ImportCopy.deliveries(deliveries),
                        ctx.session().getOriginalFilename()))
                .confirmLabel("Record " + ImportCopy.deliveries(deliveries))
                .line("stock", "Stock", deliveries,
                        "%s arriving across %s".formatted(unitPhrase, ImportCopy.products(productCount)))
                .line("vendors", "Suppliers", vendorsToCreate,
                        ImportCopy.qualified(vendorsToCreate, "new", "supplier", "suppliers")
                                + " will be added to your directory")
                .line("products", "Products", productsToCreate,
                        ImportCopy.qualified(productsToCreate, "new", "product", "products")
                                + " will be added to your catalog")
                .line("skip", "Skip", skipped,
                        "%s with nothing to record".formatted(ImportCopy.rows(skipped)));

        String dates = ImportCopy.dateRange(earliest, latest);
        if (dates != null) {
            preview.always("dates", "Dated", deliveries, dates);
        }
        if (totalCost.signum() > 0) {
            preview.always("cost", "Total cost", deliveries, ImportCopy.money(totalCost));
        }
        if (!suppliers.isEmpty()) {
            preview.always("from", "From", suppliers.size(), ImportCopy.suppliers(suppliers.size()));
        }
        if (deliveries == 0) {
            preview.blockedReason("There are no quantities to record - every row is empty, skipped, or still has "
                    + "something to fix.");
        }
        return preview.build();
    }

    // ----------------------------------------------------------------- commit

    /** One transaction, one {@code stockIn} per row, nothing reimplemented (design 8.2). */
    @Override
    public CommitOutcome commit(BatchContext ctx) {
        UUID batchId = ctx.session().getId();
        Map<String, CompanyVendor> createdVendors = createResolvedVendors(ctx);
        Map<String, Product> createdProducts = createResolvedProducts(ctx, batchId);

        int recorded = 0;
        int skipped = 0;
        long quantity = 0;

        for (ImportRowState state : ctx.states()) {
            if (state.isSkipped()) {
                state.setOutcome(ImportFields.OUTCOME_SKIPPED);
                state.setOutcomeMessage(state.value(ImportFields.QUANTITY) == null
                        ? "No quantity, so there was nothing to record."
                        : "You marked this row as skipped.");
                skipped++;
                continue;
            }
            if (!state.isCommittable()) {
                state.setOutcome(ImportFields.OUTCOME_FAILED);
                state.setOutcomeMessage("This row still had something to fix.");
                continue;
            }
            Object rawQuantity = state.value(ImportFields.QUANTITY);
            if (!(rawQuantity instanceof Number number) || number.intValue() <= 0) {
                state.setOutcome(ImportFields.OUTCOME_SKIPPED);
                state.setOutcomeMessage("No quantity, so there was nothing to record.");
                skipped++;
                continue;
            }

            Product product = state.getResolvedEntityId() != null
                    ? productRepository.findByIdAndClientId(state.getResolvedEntityId(), ctx.tenantId()).orElse(null)
                    : createdProducts.get(ValueMappings.normalizeKey(state.text(ImportFields.SKU)));
            if (product == null) {
                state.setOutcome(ImportFields.OUTCOME_FAILED);
                state.setOutcomeMessage("We could not find the product this row is for.");
                continue;
            }

            CompanyVendor vendor = resolveVendor(ctx, state, createdVendors);
            stockManagementService.stockIn(
                    product.getId(),
                    new StockInRequest(
                            number.intValue(),
                            decimalOf(state, ImportFields.UNIT_COST),
                            state.text(ImportFields.REFERENCE),
                            state.text(ImportFields.UNIT),
                            vendor == null ? null : vendor.getId(),
                            packagingUnitFor(state, product),
                            decimalOf(state, ImportFields.PACKAGING_SIZE),
                            occurredAt(state)),
                    ctx.actingUserId(),
                    batchId);
            state.setOutcome(ImportFields.OUTCOME_CREATED);
            state.setOutcomeMessage("Recorded.");
            state.setResolvedEntityId(product.getId());
            state.setResolvedEntityLabel(product.getName());
            recorded++;
            quantity += number.intValue();
        }

        List<CommitPreview.Line> lines = new ArrayList<>();
        lines.add(new CommitPreview.Line("stock", "Recorded", recorded,
                ImportCopy.deliveries(recorded) + " recorded"));
        if (!createdProducts.isEmpty()) {
            lines.add(new CommitPreview.Line("products", "Products", createdProducts.size(),
                    ImportCopy.products(createdProducts.size()) + " added to your catalog"));
        }
        if (!createdVendors.isEmpty()) {
            lines.add(new CommitPreview.Line("vendors", "Suppliers", createdVendors.size(),
                    ImportCopy.suppliers(createdVendors.size()) + " added to your directory"));
        }
        if (skipped > 0) {
            lines.add(new CommitPreview.Line("skip", "Skipped", skipped,
                    ImportCopy.rows(skipped) + " with nothing to record"));
        }
        return new CommitOutcome(
                "Recorded " + ImportCopy.deliveries(recorded), lines,
                recorded, 0, skipped, 0, createdVendors.size(), createdProducts.size(), recorded);
    }

    /**
     * The unit a quantity in packs converts by.
     *
     * <p>{@code StockManagementService.resolveBaseQuantity} treats a unit that is not the
     * product's base unit as a packaging unit and multiplies by the packaging size. It needs to
     * be told which packaging unit that is when the row's unit is not the product's own default -
     * passing it explicitly rather than letting the service fall back to the product's saved
     * packaging is what makes a delivery counted in an unusual pack size convert correctly.
     */
    private String packagingUnitFor(ImportRowState state, Product product) {
        String unit = state.text(ImportFields.UNIT);
        if (unit == null || unit.equalsIgnoreCase(product.getUnitOfMeasure())) {
            return null;
        }
        return unit;
    }

    /**
     * {@code occurred_at} from {@code received_date}, at noon UTC.
     *
     * <p>Noon rather than midnight so a date recorded from any Nigerian timezone offset still
     * falls on the day the user typed, and so a whole file of same-day deliveries does not land
     * on a timestamp that a reader would mistake for "unknown". Design 8.4: this is what FIFO
     * orders by, with {@code created_at} as the stable tiebreak, so two lots on the same date
     * still draw in the order they were written.
     */
    private OffsetDateTime occurredAt(ImportRowState state) {
        String date = state.text(ImportFields.RECEIVED_DATE);
        if (date == null) {
            return OffsetDateTime.now();
        }
        return LocalDate.parse(date).atTime(12, 0).atOffset(ZoneOffset.UTC);
    }

    private Map<String, CompanyVendor> createResolvedVendors(BatchContext ctx) {
        Map<String, CompanyVendor> created = new LinkedHashMap<>();
        for (Map.Entry<String, ValueResolution> entry : distinctCreateNewVendors(ctx).entrySet()) {
            String name = entry.getValue().payloadText("name");
            if (name != null) {
                created.put(entry.getKey(), vendorDirectory.createInline(name));
                ctx.cache().invalidate("vendors-by-name", ctx.tenantId());
            }
        }
        return created;
    }

    /**
     * Design 6.7's inline product creation, gated on MANAGE_PRODUCTS.
     *
     * <p>Collects the minimum the resolution card asked for - a name and a base unit - and
     * nothing else. A product created this way starts with no stock; the row that created it
     * immediately stocks into it, which is the whole point.
     */
    private Map<String, Product> createResolvedProducts(BatchContext ctx, UUID batchId) {
        Map<String, ValueResolution> wanted = distinctCreateNewProducts(ctx);
        if (wanted.isEmpty()) {
            return Map.of();
        }
        Client owner = sellerDirectory.findSellerOfRecord(ctx.tenantId()).orElse(null);
        Map<String, Product> created = new LinkedHashMap<>();
        for (Map.Entry<String, ValueResolution> entry : wanted.entrySet()) {
            ValueResolution resolution = entry.getValue();
            String name = resolution.payloadText("name");
            String unit = resolution.payloadText("unitOfMeasure");
            String sku = skuFor(ctx, entry.getKey());
            if (name == null || sku == null) {
                continue;
            }
            created.put(entry.getKey(), productRepository.saveAndFlush(Product.builder()
                    .name(name)
                    .sku(sku)
                    .quantityOnHand(0)
                    .incomingQuantity(0)
                    .unitOfMeasure(UnitOfMeasure.fromCodeOrLabel(unit, UnitOfMeasureRole.BASE)
                            .map(UnitOfMeasure::code)
                            .orElse(null))
                    .active(true)
                    .approvalStatus(ProductModerationRules.initialStatusFor(owner))
                    .importBatchId(batchId)
                    .build()));
            ctx.cache().invalidate(CACHE_PRODUCTS_BY_SKU, entry.getKey().toUpperCase(Locale.ROOT));
        }
        return created;
    }

    private String skuFor(BatchContext ctx, String foldedSku) {
        return ctx.states().stream()
                .map(state -> state.text(ImportFields.SKU))
                .filter(sku -> sku != null && foldedSku.equals(ValueMappings.normalizeKey(sku)))
                .findFirst()
                .orElse(null);
    }

    private Map<String, ValueResolution> distinctCreateNewProducts(BatchContext ctx) {
        return distinctCreateNew(ctx, ImportFields.SKU);
    }

    private Map<String, ValueResolution> distinctCreateNewVendors(BatchContext ctx) {
        return distinctCreateNew(ctx, ImportFields.VENDOR_NAME);
    }

    private Map<String, ValueResolution> distinctCreateNew(BatchContext ctx, String column) {
        Map<String, ValueResolution> wanted = new LinkedHashMap<>();
        for (ImportRowState state : ctx.states()) {
            if (state.isSkipped() || !state.isCommittable()) {
                continue;
            }
            String value = state.text(column);
            if (value == null) {
                continue;
            }
            ctx.valueMappings()
                    .resolutionFor(column, value)
                    .filter(ValueResolution::isCreateNew)
                    .ifPresent(resolution -> wanted.putIfAbsent(ValueMappings.normalizeKey(value), resolution));
        }
        return wanted;
    }

    private CompanyVendor resolveVendor(
            BatchContext ctx, ImportRowState state, Map<String, CompanyVendor> createdVendors) {
        String vendorName = state.text(ImportFields.VENDOR_NAME);
        if (vendorName == null) {
            return null;
        }
        Optional<ValueResolution> resolution =
                ctx.valueMappings().resolutionFor(ImportFields.VENDOR_NAME, vendorName);
        if (resolution.isPresent()) {
            ValueResolution answer = resolution.get();
            if (answer.isBlank() || answer.isSkipRows()) {
                return null;
            }
            if (answer.isCreateNew()) {
                return createdVendors.get(ValueMappings.normalizeKey(vendorName));
            }
            if (answer.isExisting()) {
                return vendorDirectory.byFoldedName(ctx.tenantId(), ctx.cache()).values().stream()
                        .filter(vendor -> vendor.getId().equals(answer.id()))
                        .findFirst()
                        .orElse(null);
            }
            if (answer.isLiteral()) {
                vendorName = answer.value();
            }
        }
        return vendorDirectory.match(ctx.tenantId(), ctx.cache(), vendorName);
    }

    /**
     * The refusal sentence, with its verb agreeing with its subject.
     *
     * <p>The single-delivery case is not rare - it is the commonest shape a blocked undo takes,
     * because an import of one delivery that has since been sold from is exactly the mistake
     * somebody notices and tries to reverse. The one template that used to serve both cases
     * produced "1 of these 1 delivery have already been sold from", which is both ungrammatical
     * and says the same number twice. Design 9.6 asks for prose; this is the sentence a person
     * would actually write for each case.
     */
    private static String blockedMessage(int blocked, long deliveries) {
        if (deliveries <= 1) {
            return "This delivery has already been sold from, so this import can't be undone all at once.";
        }
        // "all at once", not "as a batch" - see the note at the call site.
        return "%d of these %s %s already been sold from, so this import can't be undone all at once."
                .formatted(blocked, ImportCopy.deliveries(deliveries), blocked == 1 ? "has" : "have");
    }

    // ------------------------------------------------------------------- undo

    /**
     * Blocked outright if any lot this import created has been drawn from; otherwise
     * compensating {@code ADJUSTMENT} movements (design 6.6).
     *
     * <p>The block is deliberately all-or-nothing rather than per-row. A lot with a
     * {@code StockMovementAllocation} against it has been sold from, and the ledger is
     * append-only: a consumed lot cannot be un-received without unpicking the sale that consumed
     * it, which would leave the sale pointing at nothing. Undoing the other thirty-seven rows
     * and leaving three would also be a batch that no longer matches the file the user is
     * looking at, and the result screen would have to explain a partial state nobody asked for.
     * One sentence naming the three is the better answer, and it is the one design 6.6 asks for
     * word for word.
     */
    @Override
    public UndoOutcome undo(ImportSession session) {
        UUID tenantId = session.getClientId();
        UUID batchId = session.getId();
        List<StockMovement> movements =
                stockMovementRepository.findAllByClientIdAndImportBatchIdOrderByOccurredAtAsc(tenantId, batchId);
        Map<UUID, Integer> excelRowByProductId = excelRowByProductId(session);

        List<UndoOutcome.Blocker> blockers = new ArrayList<>();
        for (StockMovement movement : movements) {
            if (movement.getMovementType() != MovementType.IN) {
                continue;
            }
            if (stockMovementAllocationRepository.sumQuantityByInMovementId(movement.getId()) > 0) {
                Product product = movement.getProduct();
                blockers.add(new UndoOutcome.Blocker(
                        excelRowByProductId.getOrDefault(product.getId(), 0),
                        product.getName(),
                        "Already sold from",
                        product.getId().toString()));
            }
        }
        long inMovements = movements.stream().filter(m -> m.getMovementType() == MovementType.IN).count();
        if (!blockers.isEmpty()) {
            return UndoOutcome.blocked(
                    // "all at once", not "as a batch". Design 6.6 writes this sentence with the
                    // word "batch" in it and contract section 4 quotes it verbatim, but section
                    // 8.6 bans that word from anything a user can see - and 8.6 is the acceptance
                    // criterion. M3 hit the identical conflict and resolved it the same way in
                    // its mock, so the two now agree; if they had not, the copy the user reads
                    // would have changed the day the real backend was wired in.
                    blockedMessage(blockers.size(), inMovements),
                    blockers);
        }

        int reversed = 0;
        for (StockMovement movement : movements) {
            if (movement.getMovementType() != MovementType.IN) {
                continue;
            }
            Product product = productRepository
                    .findByIdAndClientIdForUpdate(movement.getProduct().getId(), tenantId)
                    .orElse(null);
            if (product == null) {
                continue;
            }
            product.setQuantityOnHand(product.getQuantityOnHand() - movement.getQuantity());
            stockMovementRepository.save(StockMovement.builder()
                    .product(product)
                    .movementType(MovementType.ADJUSTMENT)
                    .quantity(-movement.getQuantity())
                    .note("Reversing a delivery import that was undone")
                    .companyVendor(movement.getCompanyVendor())
                    .occurredAt(OffsetDateTime.now())
                    .importBatchId(batchId)
                    .build());
            if (movement.getCompanyVendor() != null) {
                productVendorRepository
                        .findByClientIdAndProductIdAndCompanyVendorId(
                                tenantId, product.getId(), movement.getCompanyVendor().getId())
                        .ifPresent(line -> {
                            line.setQuantityOnHandFromVendor(
                                    line.getQuantityOnHandFromVendor() - movement.getQuantity());
                            line.setTotalQuantityReceived(line.getTotalQuantityReceived() - movement.getQuantity());
                        });
            }
            reversed++;
        }

        // Products this import invented have never been anything but this import's, so they go
        // with it - same reasoning, and the same deactivate-rather-than-delete answer, as the
        // catalog handler's undo.
        List<Product> createdProducts = productRepository.findAllByClientIdAndImportBatchId(tenantId, batchId);
        for (Product product : createdProducts) {
            product.setActive(false);
        }

        List<CommitPreview.Line> lines = new ArrayList<>();
        lines.add(new CommitPreview.Line("stock", "Reversed", reversed,
                ImportCopy.deliveries(reversed) + " were reversed"));
        if (!createdProducts.isEmpty()) {
            lines.add(new CommitPreview.Line("products", "Products", createdProducts.size(),
                    ImportCopy.products(createdProducts.size()) + " this import created were switched off"));
        }
        return UndoOutcome.done("These deliveries have been reversed.", lines, createdProducts.size(), 0, reversed);
    }

    private Map<UUID, Integer> excelRowByProductId(ImportSession session) {
        Map<UUID, Integer> byProduct = new LinkedHashMap<>();
        importSessionRowRepository.findAllBySessionIdOrderByExcelRowAsc(session.getId()).forEach(row -> {
            if (row.getResolvedEntityId() != null) {
                byProduct.putIfAbsent(row.getResolvedEntityId(), row.getExcelRow());
            }
        });
        return byProduct;
    }

    // ----------------------------------------------------------------- helpers

    private Product findBySku(UUID tenantId, ImportBatchCache cache, String sku) {
        return cache.get(CACHE_PRODUCTS_BY_SKU, sku.toUpperCase(Locale.ROOT),
                key -> productRepository.findByClientIdAndSku(tenantId, sku).orElse(null));
    }

    private long vendorLineCount(UUID tenantId, ImportBatchCache cache, UUID productId) {
        Long count = cache.get(CACHE_VENDOR_LINE_COUNT, productId,
                id -> productVendorRepository.countByClientIdAndProductId(tenantId, id));
        return count == null ? 0 : count;
    }

    private List<Product> activeProducts(UUID tenantId, ImportBatchCache cache) {
        return cache.get(CACHE_ACTIVE_PRODUCTS, tenantId,
                id -> productRepository.findAllByClientIdAndActiveTrueOrderByNameAsc(id));
    }

    private static BigDecimal decimalOf(ImportRowState state, String field) {
        Object value = state.value(field);
        if (value == null) {
            return null;
        }
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
    }
}
