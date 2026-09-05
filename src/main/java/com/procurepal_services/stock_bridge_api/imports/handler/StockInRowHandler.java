package com.procurepal_services.stock_bridge_api.imports.handler;

import com.procurepal_services.stock_bridge_api.companyvendor.ProductVendorService;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.CompanyVendor;
import com.procurepal_services.stock_bridge_api.entity.ImportKind;
import com.procurepal_services.stock_bridge_api.entity.ImportSession;
import com.procurepal_services.stock_bridge_api.entity.ImportSessionRow;
import com.procurepal_services.stock_bridge_api.entity.MovementType;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.entity.ProductVendor;
import com.procurepal_services.stock_bridge_api.entity.ProductVendorPack;
import com.procurepal_services.stock_bridge_api.entity.StockMovement;
import com.procurepal_services.stock_bridge_api.imports.BatchContext;
import com.procurepal_services.stock_bridge_api.imports.CommitOutcome;
import com.procurepal_services.stock_bridge_api.imports.CommitPreview;
import com.procurepal_services.stock_bridge_api.imports.ImportBatchCache;
import com.procurepal_services.stock_bridge_api.imports.ImportCopy;
import com.procurepal_services.stock_bridge_api.imports.ImportExceptions;
import com.procurepal_services.stock_bridge_api.imports.ImportFieldDescriptor;
import com.procurepal_services.stock_bridge_api.imports.ImportFields;
import com.procurepal_services.stock_bridge_api.imports.ImportRowHandler;
import com.procurepal_services.stock_bridge_api.imports.ImportRowState;
import com.procurepal_services.stock_bridge_api.imports.NameSimilarity;
import com.procurepal_services.stock_bridge_api.imports.RowContext;
import com.procurepal_services.stock_bridge_api.imports.RowIssue;
import com.procurepal_services.stock_bridge_api.imports.RowValidation;
import com.procurepal_services.stock_bridge_api.imports.UndoOutcome;
import com.procurepal_services.stock_bridge_api.imports.UnresolvedValue;
import com.procurepal_services.stock_bridge_api.imports.ValueMappings;
import com.procurepal_services.stock_bridge_api.imports.ValueResolution;
import com.procurepal_services.stock_bridge_api.marketplace.SellerDirectory;
import com.procurepal_services.stock_bridge_api.marketplace.moderation.ProductModerationRules;
import com.procurepal_services.stock_bridge_api.product.bulk.SheetUnitOptions;
import com.procurepal_services.stock_bridge_api.product.bulk.StockInExcelService;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasure;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasureRole;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOption;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOptions;
import com.procurepal_services.stock_bridge_api.repository.ImportSessionRowRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductVendorPackRepository;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
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
 * <h2>Units - one set, three surfaces, no second opinion</h2>
 * Design 8.3 rules out per-row dependent dropdowns in the spreadsheet: they work in Excel via
 * {@code INDIRECT} and break in Google Sheets, Numbers and LibreOffice. So the sheet carries a
 * flat dropdown plus a per-row {@code how_you_count_it} reference column, and the narrowing to
 * <em>this product's</em> units happens server-side - here.
 *
 * <p>What this class must not do is decide for itself what those units are. That is what P1-1
 * was: the modal offered thirty codes, the service accepted two, and the answer depended on
 * which code path you asked. {@code UnitOptions} is the one implementation of
 * UNIT_UX_CONTRACT.md section 2.1, and this handler resolves against it, hands the resolved
 * option's factor to {@code stockIn} rather than applying it, and publishes the same set to the
 * review grid as {@code fieldOptions} (section 6.2). Three surfaces, one list.
 *
 * <p>The refusal sentence stays M2's
 * {@link StockInExcelService#unitNotStockedMessage(String, List, String)} - the file that
 * decides what a column may contain is the file that explains it - fed with
 * {@code UnitOptions.spokenPhrase} so it reads "kg or bags of 50 kg" rather than splicing a
 * capitalised picker label into the middle of a sentence.
 *
 * <h2>Prices are handed over, never converted here</h2>
 * {@code cost_per_unit} is per the row's {@code counted_in}, and it travels to
 * {@code StockManagementService.stockIn} exactly as typed, together with the unit it is per.
 * The division by the option's factor happens there, in the same call that multiplies the
 * quantity by it (section 3.2). Doing it here would either double-apply the conversion or put a
 * second copy of the rule in a second file - and the two halves of that rule living apart is
 * precisely what P0-1 was.
 */
@Component
@RequiredArgsConstructor
public class StockInRowHandler implements ImportRowHandler {

    private static final String CACHE_PRODUCTS_BY_SKU = "stock-in-products-by-sku";
    private static final String CACHE_VENDOR_LINE_COUNT = "stock-in-vendor-line-count";
    private static final String CACHE_ACTIVE_PRODUCTS = "stock-in-active-products";
    private static final String CACHE_PREFERRED_VENDOR_LINE = "stock-in-preferred-vendor-line";
    private static final String CACHE_PREFERRED_VENDOR_PACKS = "stock-in-preferred-vendor-packs";
    private static final String CACHE_ROW_VENDOR_LINE = "stock-in-row-vendor-line";
    private static final String CACHE_ROW_VENDOR_PACKS = "stock-in-row-vendor-packs";
    private static final String CACHE_PRODUCTS_BY_ID = "stock-in-products-by-id";

    /** Beyond this a backdated delivery is warned about, never blocked - design 8.4. */
    private static final int BACKDATE_WARNING_DAYS = 365;

    private final ProductRepository productRepository;
    private final ProductVendorRepository productVendorRepository;
    private final ProductVendorPackRepository productVendorPackRepository;
    private final ProductVendorService productVendorService;
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

    /**
     * {@link ImportRowHandler#confirmPack}'s stock-in implementation - MULTI_PACK_PER_VENDOR_DESIGN.md
     * section 6a. Called from the review grid's one-click "Confirm" on a {@code COUNTED_IN_NEW_PACK}
     * candidate ({@link #parseCandidatePack}), never from {@link #validate} itself, which stays
     * read-only per its own contract.
     *
     * <h2>Vendor resolution mirrors {@link #resolveVendor}, materialising eagerly instead of in
     * bulk</h2>
     * {@code resolveVendor} runs at commit time, after {@link #createResolvedVendors} has already
     * turned every distinct {@code CREATE_NEW} answer into a real {@code CompanyVendor} in one
     * pass. Confirming a pack has no such pass to ride along with - it needs one specific vendor
     * to exist right now, days before any commit - so a {@code CREATE_NEW} resolution here is
     * materialised on the spot via {@link VendorDirectory#createInline}. This is not a shortcut
     * relative to commit's behaviour, it is the same decision (the user already told the file who
     * this supplier is) executed the moment it is needed instead of batched for later - the same
     * reasoning {@code ProductVendorService.findOrCreateForReceipt} already applies to a *product*
     * for exactly this reason.
     *
     * <p>The (product, vendor) line is created here if it does not exist yet, mirroring
     * {@code ProductCatalogRowHandler.applyVendorLine}'s identical reasoning: asserting a
     * relationship a pack can hang off is not a receipt, so this does not go through
     * {@code ProductVendorService.findOrCreateForReceipt}.
     */
    @Override
    public String confirmPack(
            ImportSessionRow row,
            UUID tenantId,
            ValueMappings valueMappings,
            String packagingUnit,
            BigDecimal packagingSize) {
        UUID productId = row.getResolvedEntityId();
        if (productId == null) {
            throw new ImportExceptions.RowNotReady("This row's product isn't resolved yet - fix the sku cell first.");
        }
        Product product = productRepository
                .findByIdAndClientId(productId, tenantId)
                .orElseThrow(() -> new ImportExceptions.RowNotReady("This row's product no longer exists."));

        Map<String, Object> normalized = row.getNormalized() == null ? Map.of() : row.getNormalized();
        Object vendorNameValue = normalized.get(ImportFields.VENDOR_NAME);
        String vendorName = vendorNameValue == null ? null : vendorNameValue.toString();
        CompanyVendor vendor = vendorName == null || vendorName.isBlank()
                ? null
                : resolveVendorForConfirm(tenantId, valueMappings, vendorName);
        if (vendor == null) {
            throw new ImportExceptions.RowNotReady(
                    "We couldn't find this row's supplier - fix the vendor_name cell first.");
        }

        ProductVendor productVendor = productVendorRepository
                .findByClientIdAndProductIdAndCompanyVendorId(tenantId, productId, vendor.getId())
                .orElseGet(() -> {
                    long existingLines = productVendorRepository.countByClientIdAndProductId(tenantId, productId);
                    return productVendorRepository.saveAndFlush(ProductVendor.builder()
                            .product(product)
                            .companyVendor(vendor)
                            .isPreferred(existingLines == 0)
                            .quantityOnHandFromVendor(0)
                            .totalQuantityReceived(0)
                            .build());
                });

        ProductVendorPack pack = productVendorService.addPack(
                productId, productVendor.getId(), packagingUnit, packagingSize, null, null);
        return UnitOptions.packLabel(
                UnitOfMeasure.fromCode(pack.getPackagingUnit()).map(UnitOfMeasure::label).orElse(pack.getPackagingUnit()),
                pack.getPackagingSize(),
                product.getUnitOfMeasure());
    }

    /**
     * {@link #confirmPack}'s vendor lookup - the same four answers {@link #resolveVendor} reads
     * off {@code ValueMappings}, except {@code CREATE_NEW} is materialised right here rather than
     * looked up in a batch-wide map that only exists at commit time.
     *
     * <p>The unresolved-value card answers once for every row sharing the raw name (design 6.4),
     * so two rows naming the same new supplier share this exact {@code CREATE_NEW} resolution -
     * confirming a pack on the first row must not leave the second row's eventual confirm
     * creating a duplicate {@code CompanyVendor} of the same name. Re-checking
     * {@link VendorDirectory#match} immediately before creating is what makes this "find or
     * create" rather than "create", the same guarantee {@code ProductVendorService
     * .findOrCreateForReceipt} gives a *product* line for the identical reason - it does not
     * close a true concurrent-request race (nothing here takes a lock), but the review grid only
     * ever sends one confirm at a time for one signed-in user, which is the case this needs to
     * cover.
     */
    private CompanyVendor resolveVendorForConfirm(UUID tenantId, ValueMappings valueMappings, String vendorName) {
        Optional<ValueResolution> resolution = valueMappings.resolutionFor(ImportFields.VENDOR_NAME, vendorName);
        if (resolution.isPresent()) {
            ValueResolution answer = resolution.get();
            if (answer.isBlank() || answer.isSkipRows()) {
                return null;
            }
            if (answer.isCreateNew()) {
                CompanyVendor already = vendorDirectory.match(tenantId, new ImportBatchCache(), vendorName);
                if (already != null) {
                    return already;
                }
                String name = answer.payloadText("name");
                return name == null ? null : vendorDirectory.createInline(name);
            }
            if (answer.isExisting()) {
                return vendorDirectory.byFoldedName(tenantId, new ImportBatchCache()).values().stream()
                        .filter(vendor -> vendor.getId().equals(answer.id()))
                        .findFirst()
                        .orElse(null);
            }
            if (answer.isLiteral()) {
                vendorName = answer.value();
            }
        }
        return vendorDirectory.match(tenantId, new ImportBatchCache(), vendorName);
    }

    // ------------------------------------------------------------------ fields

    /**
     * UNIT_UX_CONTRACT.md section 5.2's column set, in template order, of which the user fills
     * one - plus {@code packaging_size}, which the sheet no longer has but old saved copies do.
     *
     * <h2>What changed, and why each change is here rather than in the sheet alone</h2>
     * The field key IS the column header (BULK_IMPORT_CONTRACT.md section 5), so M2's rename of
     * the sheet is this list's rename too: {@code unit} became {@code counted_in} and
     * {@code unit_cost} became {@code cost_per_unit}. Both old spellings stay permanent read
     * aliases in {@link com.procurepal_services.stock_bridge_api.imports.ImportColumnMapper}, so a
     * saved copy of any template we have ever published still maps.
     *
     * <p>{@code how_you_count_it} is new and read-only. It is the column that fixes the reported
     * complaint on the sheet - the valid answers printed on the row, beside the cell that asks
     * for one - and it is declared here so the grid shows the same reference text and so the
     * header is never reported as a column we did not understand.
     *
     * <p>{@code packaging_size} is last, read-only, and never read for a value. Section 5.2
     * removed it from the sheet (a delivery line must not redefine a stored product attribute -
     * UNIT_UX_REMEDIATION_PLAN.md P3-3) but requires that a number left in it on an old saved
     * template is accepted, ignored, and <em>warned about</em> rather than silently dropped. It
     * has to resolve to a field for that warning to have a cell to attach to.
     *
     * <h2>The options on {@code counted_in} are the fallback, not the answer</h2>
     * Section 6.1 keeps the descriptor's {@code options} kind-wide; the per-row narrowing travels
     * on the row as {@code fieldOptions} (section 6.2). The fallback is base units only, never
     * the nineteen PACKAGING constants the old list carried: non-negotiable 1 is that no quantity
     * field anywhere offers a unit with no conversion factor, and a Carton is not an alternative
     * way to count a product whose packs are bags - it is an unanswerable question that resolves
     * to a guaranteed 400 (P1-1, the reported complaint).
     */
    @Override
    public List<ImportFieldDescriptor> fields() {
        return List.of(
                new ImportFieldDescriptor(ImportFields.SKU, "Product code", ImportFieldDescriptor.Type.TEXT,
                        true, true, false,
                        "Your product code. This is how we match the row to your product - do not change it.", null, null),
                new ImportFieldDescriptor(ImportFields.PRODUCT_NAME, "Product", ImportFieldDescriptor.Type.TEXT,
                        false, true, false, "For your reference. We match on the code, not on this.", null, null),
                new ImportFieldDescriptor(ImportFields.HOW_YOU_COUNT_IT, "How you count it",
                        ImportFieldDescriptor.Type.TEXT, false, true, false,
                        "For your reference: the ways you can count this product. Copy one of them into "
                                + "“Counted in”. Other sizes of the same measure work too - g or t for a product "
                                + "counted in kg.", null, null),
                new ImportFieldDescriptor(ImportFields.VENDOR_NAME, ImportCopy.Labels.SUPPLIER,
                        ImportFieldDescriptor.Type.REFERENCE, false, false, false,
                        "Who this delivery came from.", null, null),
                new ImportFieldDescriptor(ImportFields.QUANTITY, "Quantity", ImportFieldDescriptor.Type.INTEGER,
                        false, false, true,
                        "How much of this product arrived, counted in whatever “Counted in” says. Leave it "
                                + "empty for products you did not receive.", null, null),
                ImportFieldDescriptor.enumeration(ImportFields.COUNTED_IN, ImportCopy.Labels.COUNTED_IN, false,
                                "Which of the ways in “How you count it” the number beside it is in - the product's own "
                                        + "stock unit, or the pack you buy it by.",
                                RowValues.options(UnitOfMeasure.baseUnits()))
                        // Same rule as the catalog sheet's opening_stock_counted_in: a column
                        // that states what a quantity MEANS must stay visible wherever that
                        // quantity is, or the grid hides the only way to correct it.
                        .withQualifies(ImportFields.QUANTITY),
                ImportFieldDescriptor.of(ImportFields.COST_PER_UNIT, ImportCopy.Labels.COST_PER_COUNTED_IN_UNIT,
                        ImportFieldDescriptor.Type.MONEY, false,
                        "Per whatever this row's “Counted in” says - per bag if it says Bag, per kg if it says kg."),
                ImportFieldDescriptor.of(ImportFields.RECEIVED_DATE, "Date received",
                        ImportFieldDescriptor.Type.DATE, false,
                        "When the delivery actually arrived. We use this to work out which stock was sold first."),
                ImportFieldDescriptor.text(ImportFields.WAYBILL_OR_INVOICE_NO, "Waybill or invoice",
                        "So you can find this delivery again."),
                new ImportFieldDescriptor(ImportFields.PACKAGING_SIZE, ImportCopy.Labels.UNITS_PER_PACK,
                        ImportFieldDescriptor.Type.NUMBER, false, true, false,
                        "No longer used. We take the pack from your product setup now, so a number here is "
                                + "ignored.", null, null));
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
            readRemainingColumns(ctx, out, subject, null, quantity);
            return out.build();
        }
        out.resolvedTo(product.getId(), product.getName());
        subject = product.getName();

        readRemainingColumns(ctx, out, subject, product, quantity);
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
            RowContext ctx, RowValidation.Builder out, String subject, Product product, Integer quantity) {
        List<UnitOption> options = product == null
                ? List.of()
                : unitOptionsFor(
                        ctx.tenantId(),
                        ctx.cache(),
                        product,
                        ctx.text(ImportFields.VENDOR_NAME),
                        ctx.resolution(ImportFields.VENDOR_NAME));
        UnitOption countedIn = validateCountedIn(ctx, out, product, options, subject);
        RowValues.money(ctx, out, ImportFields.COST_PER_UNIT, ImportCopy.Labels.COST_PER_COUNTED_IN_UNIT, subject);
        warnOnIgnoredPackagingSize(ctx, out);
        validateReceivedDate(ctx, out, subject);
        out.value(ImportFields.WAYBILL_OR_INVOICE_NO, ctx.text(ImportFields.WAYBILL_OR_INVOICE_NO));
        describeUnits(out, subject, product, options, countedIn, quantity);
    }

    /**
     * UNIT_UX_CONTRACT.md section 3.1's resolution, done against the row's product rather than
     * against a hand-rolled two-branch comparison.
     *
     * <h2>What this replaces</h2>
     * The previous version asked two questions - "is this the base unit?" and "is this the
     * packaging unit?" - which is a second implementation of "which units does this product
     * accept", and a second implementation is what P1-1 was: the modal offered thirty codes, the
     * service accepted two, and the answer depended on which code path you asked. There is now
     * one list, {@code UnitOptions} builds it, and every surface resolves against it.
     *
     * <p>The refusal names every valid answer, in the grammar a person would use -
     * {@code UnitOptions.spokenPhrase} turns the picker label "Bag of 50 kg" into the
     * mid-sentence "bags of 50 kg", so the message reads "Rice 50kg is counted in kg or bags of
     * 50 kg - we don't know how to count it in cartons" rather than splicing a capitalised label
     * into the middle of a sentence. The sentence template itself stays in
     * {@link StockInExcelService#unitNotStockedMessage(String, List, String)}, next to the column
     * and the dropdown it is about, so the sheet and the grid cannot drift.
     *
     * @return the option this row's quantity and price are counted in, or null when the cell
     *     could not be resolved. A blank cell resolves to the product's stock unit, which is what
     *     an absent {@code unit} means everywhere else (non-negotiable 8).
     */
    private UnitOption validateCountedIn(
            RowContext ctx,
            RowValidation.Builder out,
            Product product,
            List<UnitOption> options,
            String subject) {
        String raw = ctx.text(ImportFields.COUNTED_IN);
        if (raw == null) {
            out.value(ImportFields.COUNTED_IN, null);
            return product == null ? null : UnitOptions.stockUnitOption(options).orElse(null);
        }

        // SheetUnitOptions.resolve, not UnitOfMeasure.fromCodeOrLabel: the cell may contain a
        // composed pack label ("Bag of 50 kg") because that is what M2's template writes into it,
        // and that string is not a unit - it is a unit and a size. M2 owns undoing that
        // composition; re-deriving it here would be the second copy again.
        Optional<UnitOfMeasure> resolved = SheetUnitOptions.resolve(raw);
        if (resolved.isEmpty()) {
            // MULTI_PACK_PER_VENDOR_DESIGN.md section 6a's third parse outcome: before assuming
            // this is a plain mistake, see whether it is a DELIBERATE declaration of a pack
            // nobody has configured yet - "100 kg", or "Jumbo bag of 100 kg". A vendor turning up
            // in a new pack is routine, not exceptional, and the sheet has no other way to say so
            // than typing the size in the one cell that asks about it.
            if (product != null && emitCandidatePackIssue(out, parseCandidatePack(raw, product))) {
                return null;
            }
            ImportFieldDescriptor.Option suggestion = RowValues.closestUnit(raw, UnitOfMeasureRole.BASE);
            out.issue(RowIssue.error(
                    ImportFields.COUNTED_IN, "UNIT_NOT_RECOGNISED",
                    suggestion == null
                            ? "We don't recognise %s as a way to count %s."
                                    .formatted(ImportCopy.quote(raw), subject)
                            : "We don't recognise %s as a way to count %s. Did you mean %s?"
                                    .formatted(ImportCopy.quote(raw), subject, suggestion.label()),
                    suggestion));
            out.value(ImportFields.COUNTED_IN, null);
            return null;
        }

        String code = resolved.get().code();
        if (product == null) {
            // An unresolved SKU, or one answered with "create this product". Its unit set is
            // whatever the resolution card said it would be, so there is nothing to check the
            // cell against yet - keep the code and let the next pass judge it.
            out.value(ImportFields.COUNTED_IN, code);
            return null;
        }

        Optional<UnitOption> option = UnitOptions.resolve(options, code);
        if (option.isEmpty()) {
            // Same second chance section 6a already gives unparseable text (above): a composed
            // label that DOES resolve to a real unit but not to one this row currently has on
            // file is exactly as much a re-declarable candidate as raw text was. Without this, a
            // cell that ever lands here - because an earlier confirm attempt wrote the composed
            // label back before its pack could be recognised, or because the matching pack was
            // since edited or removed - is stuck on the plain "did you mean" dropdown forever:
            // {@code parseCandidatePack} was only ever tried on the branch above, so a value that
            // parses as a unit-of-measure never reached it, no matter how visibly it named a size
            // nobody has configured.
            if (emitCandidatePackIssue(out, parseCandidatePack(raw, product))) {
                return null;
            }
            UnitOption stockUnit = UnitOptions.stockUnitOption(options).orElse(null);
            out.issue(RowIssue.error(
                    ImportFields.COUNTED_IN, "UNIT_NOT_STOCKED",
                    StockInExcelService.unitNotStockedMessage(
                            subject,
                            options.stream().map(UnitOptions::spokenPhrase).toList(),
                            UnitOptions.spokenPhraseOfSubmitted(raw)),
                    stockUnit == null
                            ? null
                            : new ImportFieldDescriptor.Option(stockUnit.code(), stockUnit.label())));
            out.value(ImportFields.COUNTED_IN, null);
            return null;
        }
        out.value(ImportFields.COUNTED_IN, option.get().code());
        return option.get();
    }

    /**
     * The three things the review grid needs in order to show a unit rather than assume one -
     * UNIT_UX_CONTRACT.md section 6.2.
     *
     * <ul>
     *   <li>{@code how_you_count_it}: recomputed from the product rather than echoed from the
     *       file, so the reference column states today's truth even on a sheet saved last month.
     *   <li>{@code fieldOptions}: this row's own unit set, which turns the "Counted in" cell from
     *       a thirty-option select into a two-option one. The kind-wide list on the descriptor
     *       stays as the fallback for rows whose product has not resolved (section 6.1).
     *   <li>{@code baseQuantityText}: {@code "= 2,000 kg"}, the ledger's number sitting under the
     *       user's number. Non-negotiable 3 on the grid.
     * </ul>
     *
     * <p>The conversion is checked for overflow here rather than left to the commit. Two billion
     * bags of fifty is a mistyped cell, not a server fault, and it has to come back as an
     * outlined cell like every other bad number - {@code stock_movements.quantity} is an int, so
     * a commit would otherwise fail mid-transaction and roll back the whole file.
     */
    private void describeUnits(
            RowValidation.Builder out,
            String subject,
            Product product,
            List<UnitOption> options,
            UnitOption countedIn,
            Integer quantity) {
        if (product == null || options.isEmpty()) {
            return;
        }
        out.value(ImportFields.HOW_YOU_COUNT_IT, SheetUnitOptions.howYouCountIt(options));
        out.value(ImportFields.FIELD_OPTIONS, Map.of(ImportFields.COUNTED_IN, options.stream()
                .map(option -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("value", option.code());
                    entry.put("label", option.label());
                    return entry;
                })
                .toList()));

        if (countedIn == null || quantity == null || quantity <= 0) {
            return;
        }
        Long baseQuantity = toStockUnits(countedIn, quantity);
        if (baseQuantity == null) {
            out.error(ImportFields.QUANTITY, "NUMBER_TOO_LARGE",
                    "That is a larger delivery of %s than we can record.".formatted(subject));
            out.value(ImportFields.QUANTITY, null);
            return;
        }
        if (countedIn.isStockUnit()) {
            // Nothing to convert. "= 20 kg" under a cell reading 20 is noise, and section 6.2
            // says null rather than a restatement.
            return;
        }
        out.value(ImportFields.BASE_QUANTITY_TEXT,
                ImportCopy.baseQuantityText(baseQuantity, product.getUnitOfMeasure()));
    }

    /**
     * Contract section 5.2: {@code packaging_size} is accepted, ignored, and warned about.
     *
     * <p>Reached only after the blank-quantity rule has already returned (contract section 8.11),
     * which is what keeps this proportionate: a four-hundred-row pre-filled sheet recording a
     * six-line delivery produces six of these, not four hundred. A warning on every row of a file
     * is the same as no warning at all.
     *
     * <p>The normalized value is always null. The column is read so that it can be explicitly
     * ignored rather than silently, never so that it can be used - a delivery line must not
     * redefine a stored product attribute (UNIT_UX_REMEDIATION_PLAN.md P3-3).
     */
    private void warnOnIgnoredPackagingSize(RowContext ctx, RowValidation.Builder out) {
        out.value(ImportFields.PACKAGING_SIZE, null);
        if (ctx.text(ImportFields.PACKAGING_SIZE) == null) {
            return;
        }
        out.warning(ImportFields.PACKAGING_SIZE, "PACKAGING_SIZE_IGNORED",
                StockInExcelService.PACKAGING_SIZE_IGNORED_WARNING);
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
    /**
     * {@code sku} and {@code vendor_name} - the two columns this handler asks questions about and
     * answers itself, in {@link #resolveProduct} and {@link #resolveVendor}. Both point at
     * entities rather than holding values, so substituting text into them generically would be
     * the engine guessing at something only the handler can do.
     */
    @Override
    public java.util.Set<String> selfResolvedColumns() {
        return java.util.Set.of(ImportFields.SKU, ImportFields.VENDOR_NAME);
    }

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
     * Design 9.4's stock-in wording, with UNIT_UX_CONTRACT.md section 6.3's correction:
     * "Record 18 deliveries - 9,500 kg (190 bags) across 12 products from 4 suppliers, dated
     * 12 Jan - 3 Feb. Total cost N8,420,000."
     *
     * <h2>P0-4, and why this is the most important method in the class</h2>
     * It used to do {@code quantity += number.intValue()} over the RAW entered quantities and
     * then label the sum with the single unit if the file happened to use one, else the word
     * "units". A sheet of 190 bags therefore previewed as "190 BAG" while the ledger recorded
     * 9,500 kg, and a mixed-unit sheet previewed as a sum of numbers denominated in different
     * things - a quantity that exists nowhere and means nothing.
     *
     * <p>A confirm screen exists for exactly one reason: to prevent surprise. One that states a
     * number the ledger never writes is worse than no confirm screen at all, because it converts
     * a user's caution into false confidence. So every row is converted to its product's stock
     * unit BEFORE it is added to anything, and the sentence states both halves - what the ledger
     * will record, and, in brackets, what the user typed.
     *
     * <h2>When the parenthetical is dropped</h2>
     * Section 6.3: only when the file genuinely used one entry unit throughout does "(190 bags)"
     * mean anything. Two entry units, or products counted in two different stock units, and the
     * restatement would be a second lie replacing the first - so only the stock-unit totals are
     * shown, one per stock unit, joined with "and" rather than summed across categories (adding
     * kilograms to pieces is not a conversion, contract section 2.2).
     */
    @Override
    public CommitPreview preview(BatchContext ctx) {
        int deliveries = 0;
        // Insertion-ordered: the first product in the file names the unit the sentence leads with.
        Map<String, Long> stockUnitTotals = new LinkedHashMap<>();
        Map<String, Long> enteredTotals = new LinkedHashMap<>();
        Map<String, UnitOption> enteredOptions = new LinkedHashMap<>();
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

            Product product = productOf(ctx, state);
            UnitOption option = optionFor(ctx, product, state, state.text(ImportFields.COUNTED_IN));
            Long converted = option == null ? null : toStockUnits(option, number.intValue());
            // A row whose product has not been created yet converts by 1: the resolution card
            // collected a base unit and nothing else, so the number the user typed IS the number
            // the ledger will take. Falling back to the raw value here is not the bare sum P0-4
            // is about - it is a genuine stock-unit quantity for a product with no pack.
            stockUnitTotals.merge(
                    stockUnitSymbolOf(ctx, product, state),
                    converted == null ? (long) number.intValue() : converted,
                    Long::sum);

            String enteredCode = option == null ? ImportFields.QUANTITY : option.code();
            enteredTotals.merge(enteredCode, (long) number.intValue(), Long::sum);
            if (option != null) {
                enteredOptions.putIfAbsent(enteredCode, option);
            }

            if (state.getResolvedEntityId() != null) {
                products.add(state.getResolvedEntityId());
            }
            if (state.text(ImportFields.VENDOR_NAME) != null) {
                suppliers.add(ValueMappings.normalizeKey(state.text(ImportFields.VENDOR_NAME)));
            }
            Object cost = state.value(ImportFields.COST_PER_UNIT);
            if (cost != null) {
                // Money spent, and that is basis-independent: a price per bag times a count of
                // bags is the same naira as a price per kg times a count of kg. This one line
                // was never wrong, and converting either half of it would have made it so.
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
        String quantityPhrase = quantityPhrase(stockUnitTotals, enteredTotals, enteredOptions);

        CommitPreview.Builder preview = CommitPreview.builder()
                .headline("Record %s from %s".formatted(ImportCopy.deliveries(deliveries),
                        ctx.session().getOriginalFilename()))
                .confirmLabel("Record " + ImportCopy.deliveries(deliveries))
                .line("stock", "Stock", deliveries,
                        "%s across %s".formatted(quantityPhrase, ImportCopy.products(productCount)))
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

    /**
     * Section 6.3's sentence fragment: the ledger's number, and the user's number in brackets
     * when - and only when - the whole file used one way of counting.
     */
    private String quantityPhrase(
            Map<String, Long> stockUnitTotals,
            Map<String, Long> enteredTotals,
            Map<String, UnitOption> enteredOptions) {
        String ledger = ImportCopy.quantityTotals(stockUnitTotals);
        if (stockUnitTotals.size() != 1 || enteredTotals.size() != 1) {
            return ledger;
        }
        UnitOption only = enteredOptions.get(enteredTotals.keySet().iterator().next());
        if (only == null || only.isStockUnit()) {
            // Typed in the stock unit already, so the bracket would repeat the number to its own
            // left. Non-negotiable 3 asks for both forms to appear together, not for one form to
            // appear twice.
            return ledger;
        }
        return ledger + " (" + ImportCopy.enteredQuantityPhrase(enteredTotals.values().iterator().next(), only) + ")";
    }

    /**
     * The stock unit a row's quantity will land in, as a short symbol.
     *
     * <p>For a product that does not exist yet, the answer is whatever the inline-create card
     * collected - the resolution payload's {@code unitOfMeasure} - because that is the unit the
     * product will be created with a moment later, and a preview that named a different one would
     * be describing a different import.
     */
    private String stockUnitSymbolOf(BatchContext ctx, Product product, ImportRowState state) {
        String code = product != null ? product.getUnitOfMeasure() : createdProductUnitOf(ctx, state);
        String symbol = UnitOptions.symbolOf(code);
        return symbol.isEmpty() ? UnitOptions.NO_STOCK_UNIT_LABEL : symbol;
    }

    private String createdProductUnitOf(BatchContext ctx, ImportRowState state) {
        String sku = state.text(ImportFields.SKU);
        if (sku == null) {
            return null;
        }
        return ctx.valueMappings()
                .resolutionFor(ImportFields.SKU, sku)
                .filter(ValueResolution::isCreateNew)
                .map(resolution -> resolution.payloadText("unitOfMeasure"))
                .flatMap(unit -> UnitOfMeasure.fromCodeOrLabel(unit, UnitOfMeasureRole.BASE))
                .map(UnitOfMeasure::code)
                .orElse(null);
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
            UnitOption option = optionFor(ctx, product, state, state.text(ImportFields.COUNTED_IN));
            stockManagementService.stockIn(
                    product.getId(),
                    new StockInRequest(
                            number.intValue(),
                            // cost_per_unit is per the row's "Counted in", and it is handed over
                            // exactly as typed. UNIT_UX_CONTRACT.md section 3.2's division by the
                            // option's factor happens once, inside StockManagementService, beside
                            // the multiplication of the quantity by the same factor - which is
                            // the entire point of M1 resolving the two together (P0-1 was the two
                            // halves living apart). Dividing here as well would halve every
                            // imported cost, and dividing here INSTEAD would put a second copy of
                            // the rule in a second file.
                            decimalOf(state, ImportFields.COST_PER_UNIT),
                            state.text(ImportFields.WAYBILL_OR_INVOICE_NO),
                            state.text(ImportFields.COUNTED_IN),
                            vendor == null ? null : vendor.getId(),
                            packOverrideUnit(option),
                            packOverrideSize(option),
                            occurredAt(state),
                            // Contract section 3.4 and non-negotiable 7: a per-delivery pack never
                            // mutates stored configuration without an explicit opt-in on the same
                            // screen. A spreadsheet has no such screen and the review grid offers
                            // no such checkbox, so the answer is false - written out rather than
                            // left to the shorter constructor's implicit null, because "nobody
                            // said" and "no" reading alike is a thing a reader should not have to
                            // go and confirm.
                            false),
                    ctx.actingUserId(),
                    batchId);
            state.setOutcome(ImportFields.OUTCOME_CREATED);
            state.setOutcomeMessage("Recorded.");
            state.setResolvedEntityId(product.getId());
            state.setResolvedEntityLabel(product.getName());
            recorded++;
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
     * This delivery's pack, handed to {@code stockIn} so that the option the review grid resolved
     * against is the option the ledger resolves against.
     *
     * <h2>Why this is not simply the row's unit code, as it used to be</h2>
     * The old version passed the row's {@code unit} as {@code packagingUnit} whenever it was not
     * the product's base unit, and let the service fall back to the product's own
     * {@code packagingSize}. Under M1's rewritten {@code resolveEntry} that is actively wrong: a
     * row counted in tonnes would be handed "T" as a packaging unit with the product's pack size
     * as its factor, so a tonne of a 50 kg-bagged product would be recorded as 50 kg. Tonnes are
     * a same-category base unit with a static factor of 1,000 (contract section 2.2), not a pack.
     *
     * <p>So the override is derived from the resolved {@link UnitOption} instead, and only for a
     * genuine pack - an option that is neither the stock unit nor a BASE-role unit. For a pack
     * that is already the product's own, {@code UnitOptions.extendedWith} replaces the entry with
     * an identical one and nothing changes. For a pack that came from the supplier's standing
     * default, this is what carries it across: without it the service, which builds its set from
     * the product alone, would refuse a unit the sheet had legitimately offered.
     *
     * <p>It also snapshots the pack onto the resulting {@code StockMovement}, which is what makes
     * "what did this delivery arrive as" answerable later without inferring it from a
     * configuration that may since have changed.
     */
    private static String packOverrideUnit(UnitOption option) {
        return isPack(option) ? option.code() : null;
    }

    /** The size half of {@link #packOverrideUnit}; the two are only ever passed together. */
    private static BigDecimal packOverrideSize(UnitOption option) {
        return isPack(option) ? option.factorToStockUnit() : null;
    }

    /**
     * A pack is an option that is neither the stock unit nor one of section 2.2's same-category
     * base units - i.e. a container whose factor came from a product's or a supplier's
     * configuration rather than from the static table.
     */
    private static boolean isPack(UnitOption option) {
        // Was inferred from UnitOfMeasure.role(); that only worked while the role split was a
        // hard gate. A turmeric packed in 34 g PIECEs has a pack whose code is declared BASE, so
        // the inference reported "no pack" on a delivery that had one. The set records it now.
        return option != null && option.isPack();
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

    /**
     * Matches a deliberate size declaration typed into {@code counted_in} - {@code "100 kg"} or
     * {@code "Jumbo bag of 100 kg"} - the same grammar {@code UnitOptions.packLabel} itself
     * writes ({@code "<word> of <size> <unit>"}), so this reads what the sheet already produces
     * rather than inventing a second format. Group 1 is the optional container word, group 2 the
     * size, group 3 the trailing unit word.
     */
    private static final Pattern CANDIDATE_PACK_PATTERN =
            Pattern.compile("^(?:(.+?)\\s+of\\s+)?([\\d,]+(?:\\.\\d+)?)\\s*([a-zA-Z]+)$", Pattern.CASE_INSENSITIVE);

    /**
     * MULTI_PACK_PER_VENDOR_DESIGN.md section 6a's parse-time handling: does {@code raw} name a
     * size in the product's own stock unit, deliberately, rather than being a plain mistake?
     *
     * <p>The trailing unit word must match the product's stock unit (its code, symbol, or label) -
     * without that check, "100 boxes" would parse as "100 kg" for a KG product, which is exactly
     * the silent-wrong-number failure mode this whole remediation exists to prevent. The leading
     * word, when present, is resolved against a real packaging code if one matches ({@code "bag"}
     * → {@code BAG}) so a recognisable word is not needlessly generalised; an unrecognised or
     * absent word falls back to {@link UnitOfMeasure#PACK}, the generic container this catalog
     * already has for exactly this case - never a code invented on the spot.
     *
     * @return empty when {@code raw} does not parse as a size at all, or when the product has no
     *     stock unit to compare the trailing word against.
     */
    private Optional<CandidatePack> parseCandidatePack(String raw, Product product) {
        Matcher matcher = CANDIDATE_PACK_PATTERN.matcher(raw.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        BigDecimal size;
        try {
            size = new BigDecimal(matcher.group(2).replace(",", ""));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (size.signum() <= 0) {
            return Optional.empty();
        }

        Optional<UnitOfMeasure> stockUnit = UnitOfMeasure.fromCode(product.getUnitOfMeasure());
        if (stockUnit.isEmpty()) {
            return Optional.empty();
        }
        String trailingUnit = matcher.group(3);
        boolean matchesStockUnit = trailingUnit.equalsIgnoreCase(stockUnit.get().code())
                || trailingUnit.equalsIgnoreCase(stockUnit.get().symbol())
                || trailingUnit.equalsIgnoreCase(stockUnit.get().label());
        if (!matchesStockUnit) {
            return Optional.empty();
        }

        String word = matcher.group(1);
        UnitOfMeasure container = word == null
                ? UnitOfMeasure.PACK
                : UnitOfMeasure.fromCodeOrLabel(word.trim())
                        .filter(u -> u.canServeAs(UnitOfMeasureRole.PACKAGING))
                        .orElse(UnitOfMeasure.PACK);

        String label = UnitOptions.packLabel(container.label(), size, product.getUnitOfMeasure());
        return Optional.of(new CandidatePack(container.code(), size, label));
    }

    /** A pack parsed from free text, not yet confirmed or persisted - see {@link #parseCandidatePack}. */
    private record CandidatePack(String packagingUnit, BigDecimal packagingSize, String label) {
    }

    /**
     * The one {@code COUNTED_IN_NEW_PACK} issue both of {@link #validateCountedIn}'s "not a known
     * answer" branches raise the same way - unparseable text, and a composed label that parses
     * but does not match anything this row currently has on file. Returns whether it fired, so
     * each call site can {@code return null} in the same breath rather than repeating the issue
     * and the early return around two different callers.
     */
    private boolean emitCandidatePackIssue(RowValidation.Builder out, Optional<CandidatePack> candidate) {
        if (candidate.isEmpty()) {
            return false;
        }
        CandidatePack pack = candidate.get();
        out.issue(RowIssue.error(
                ImportFields.COUNTED_IN, "COUNTED_IN_NEW_PACK",
                "Reads as a new pack: %s. Confirm to add it, or edit if that's not right."
                        .formatted(pack.label()),
                new ImportFieldDescriptor.Option(pack.packagingUnit() + "|" + pack.packagingSize(), pack.label())));
        out.value(ImportFields.COUNTED_IN, null);
        return true;
    }

    /**
     * This row's product's unit set - contract section 2.1 steps 1 to 4, including the preferred
     * supplier's own pack AND, when this row names a different supplier, that supplier's packs
     * too ({@link #rowVendorPacks}).
     *
     * <h2>Why the supplier's pack is in here</h2>
     * Because it is in the sheet. {@code StockInTemplateService} builds every template row's
     * {@code how_you_count_it} from {@code SheetUnitOptions.forRow}, which includes the preferred
     * supplier's default pack, and the {@code counted_in} dropdown offers it. A server that then
     * resolved against the product alone would reject a value its own template had just told the
     * user was valid - which is P1-1 in a new costume: two answers to "which units does this
     * product accept", differing by which code path you asked.
     *
     * <h2>Why the ROW's own vendor also gets a say</h2>
     * A row is free to name any of the product's suppliers, not only the preferred one - that is
     * the entire point of {@code vendor_name} being editable. Before this, a pack that supplier
     * genuinely had on file (added by hand on the Vendors tab, or moments ago via
     * {@link #confirmPack}) was invisible here whenever that supplier happened not to be the
     * product's preferred one: {@code unitOptionsFor} asked only "what can the preferred supplier
     * count this in", and a correctly-created, correctly-named pack for anyone else answered
     * "we don't know how to count it" regardless. That is the same P1-1 shape one supplier over -
     * the fix is the same one this method already makes for the preferred supplier, extended to
     * whichever supplier the row actually names.
     *
     * <p>One lookup per product per pass, memoised on the batch cache: the grid re-validates the
     * whole file on every cell repair, so an un-cached query here would be paid on every
     * keystroke-settle rather than once per upload.
     */
    private List<UnitOption> unitOptionsFor(
            UUID tenantId,
            ImportBatchCache cache,
            Product product,
            String vendorName,
            Optional<ValueResolution> vendorResolution) {
        ProductVendor preferred = cache.get(CACHE_PREFERRED_VENDOR_LINE, product.getId(),
                id -> productVendorRepository
                        .findByClientIdAndProductIdAndIsPreferredTrue(tenantId, id)
                        .orElse(null));
        // Every one of the preferred supplier's packs (MULTI_PACK_PER_VENDOR_DESIGN.md sections
        // 4-6), not just a single default - a vendor is no longer limited to one, and this is the
        // one place that needs to know, since it feeds both how_you_count_it and counted_in.
        List<UnitOptions.PackSpec> preferredPacks = preferred == null
                ? List.of()
                : cache.get(CACHE_PREFERRED_VENDOR_PACKS, preferred.getId(),
                                id -> productVendorPackRepository.findAllByProductVendorIdOrderByIsDefaultDescCreatedAtAsc(id))
                        .stream()
                        .map(pack -> new UnitOptions.PackSpec(pack.getPackagingUnit(), pack.getPackagingSize()))
                        .toList();
        List<UnitOptions.PackSpec> rowPacks =
                rowVendorPacks(tenantId, cache, product.getId(), vendorName, vendorResolution);
        List<UnitOptions.PackSpec> packs = rowPacks.isEmpty()
                ? preferredPacks
                : Stream.concat(preferredPacks.stream(), rowPacks.stream()).toList();
        return UnitOptions.forProductAndSupplier(
                product.getUnitOfMeasure(), product.getPackagingUnit(), product.getPackagingSize(), packs);
    }

    /**
     * The packs of whichever {@code CompanyVendor} this specific row names, when that resolves to
     * a real, already-persisted one - see {@link #unitOptionsFor}. Read-only, deliberately: {@code
     * validate} must stay pure (the class javadoc's own rule), so a {@code CREATE_NEW} answer that
     * has not been materialised yet (only {@link #confirmPack} does that, on demand) simply
     * contributes nothing here, which is the correct answer for a supplier with no packs on file
     * yet anyway.
     */
    private List<UnitOptions.PackSpec> rowVendorPacks(
            UUID tenantId,
            ImportBatchCache cache,
            UUID productId,
            String vendorName,
            Optional<ValueResolution> vendorResolution) {
        if (vendorName == null) {
            return List.of();
        }
        CompanyVendor vendor = vendorResolution
                .filter(ValueResolution::isExisting)
                .map(answer -> vendorDirectory.byFoldedName(tenantId, cache).values().stream()
                        .filter(candidate -> candidate.getId().equals(answer.id()))
                        .findFirst()
                        .orElse(null))
                .orElseGet(() -> vendorDirectory.match(tenantId, cache, vendorName));
        if (vendor == null) {
            return List.of();
        }
        String cacheKey = productId + "|" + vendor.getId();
        ProductVendor productVendor = cache.get(CACHE_ROW_VENDOR_LINE, cacheKey,
                ignored -> productVendorRepository
                        .findByClientIdAndProductIdAndCompanyVendorId(tenantId, productId, vendor.getId())
                        .orElse(null));
        if (productVendor == null) {
            return List.of();
        }
        return cache.get(CACHE_ROW_VENDOR_PACKS, productVendor.getId(),
                        id -> productVendorPackRepository.findAllByProductVendorIdOrderByIsDefaultDescCreatedAtAsc(id))
                .stream()
                .map(pack -> new UnitOptions.PackSpec(pack.getPackagingUnit(), pack.getPackagingSize()))
                .toList();
    }

    /** {@link #unitOptionsFor} plus section 3.1's resolution, for the batch phases. */
    private UnitOption optionFor(BatchContext ctx, Product product, ImportRowState state, String countedIn) {
        if (product == null) {
            return null;
        }
        String vendorName = state.text(ImportFields.VENDOR_NAME);
        Optional<ValueResolution> vendorResolution = vendorName == null
                ? Optional.empty()
                : ctx.valueMappings().resolutionFor(ImportFields.VENDOR_NAME, vendorName);
        return UnitOptions.resolve(
                        unitOptionsFor(ctx.tenantId(), ctx.cache(), product, vendorName, vendorResolution), countedIn)
                .orElse(null);
    }

    /**
     * The product a batch-phase row is about. Preview and commit both need it and neither
     * re-validates, so it is read back through the same cache {@code validate} filled.
     */
    private Product productOf(BatchContext ctx, ImportRowState state) {
        UUID resolved = state.getResolvedEntityId();
        if (resolved != null) {
            return ctx.cache().get(CACHE_PRODUCTS_BY_ID, resolved,
                    id -> productRepository.findByIdAndClientId(id, ctx.tenantId()).orElse(null));
        }
        String sku = state.text(ImportFields.SKU);
        return sku == null ? null : findBySku(ctx.tenantId(), ctx.cache(), sku);
    }

    /**
     * {@code UnitOption.toStockUnitQuantity} with the overflow turned into an answer rather than
     * an exception.
     *
     * <p>{@code stock_movements.quantity} is an int, and the conversion multiplies a number the
     * user typed by a factor they configured, so the product of two individually legal values can
     * exceed it. M1's method throws {@code ArithmeticException} there, correctly - it has no
     * subject to name and no cell to outline. This module has both, so it catches the throw and
     * lets the caller raise a cell error instead of failing a 5,000-row commit half way through.
     *
     * @return the quantity in stock units, or null when it does not fit.
     */
    private static Long toStockUnits(UnitOption option, int enteredQuantity) {
        try {
            return (long) option.toStockUnitQuantity(enteredQuantity);
        } catch (ArithmeticException tooLarge) {
            return null;
        }
    }

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
