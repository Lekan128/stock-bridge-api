package com.procurepal_services.stock_bridge_api.imports.handler;

import com.procurepal_services.stock_bridge_api.companyvendor.ProductVendorService;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.CompanyVendor;
import com.procurepal_services.stock_bridge_api.entity.ImportKind;
import com.procurepal_services.stock_bridge_api.entity.ImportMode;
import com.procurepal_services.stock_bridge_api.entity.ImportSession;
import com.procurepal_services.stock_bridge_api.entity.MovementType;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.entity.ProductVendor;
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
import com.procurepal_services.stock_bridge_api.imports.RowContext;
import com.procurepal_services.stock_bridge_api.imports.RowIssue;
import com.procurepal_services.stock_bridge_api.imports.RowValidation;
import com.procurepal_services.stock_bridge_api.imports.UndoOutcome;
import com.procurepal_services.stock_bridge_api.imports.UnresolvedValue;
import com.procurepal_services.stock_bridge_api.imports.ValueMappings;
import com.procurepal_services.stock_bridge_api.imports.ValueResolution;
import com.procurepal_services.stock_bridge_api.marketplace.SellerDirectory;
import com.procurepal_services.stock_bridge_api.marketplace.moderation.ProductModerationRules;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasure;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasureRole;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductVendorRepository;
import com.procurepal_services.stock_bridge_api.repository.StockMovementAllocationRepository;
import com.procurepal_services.stock_bridge_api.repository.StockMovementRepository;
import com.procurepal_services.stock_bridge_api.stock.StockManagementService;
import com.procurepal_services.stock_bridge_api.stock.dto.StockInRequest;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The PRODUCT_CATALOG row handler: "what do we stock?"
 *
 * <p>Answers identity, not events (design 6.7). Runs rarely - onboarding, a new product line, a
 * supplier's updated price list - and the user brings the rows. Everything about it that is not
 * a fact about a row lives in {@code ImportSessionService}; see {@link ImportRowHandler} for why
 * the seam is drawn there.
 *
 * <h2>The three update rules, and why each one is loud</h2>
 * Design 6.3 gives the update path three constraints, all inherited from decisions made
 * elsewhere, and BULK_IMPORT_CONTRACT.md section 8 turns each into a non-negotiable:
 *
 * <ol>
 *   <li><b>{@code quantity_on_hand} is ignored on an update, and the grid says so</b> (8.8).
 *       Quantity moves through the ledger or not at all (design 3), so an update row's quantity
 *       column has nowhere to go. Silently dropping it would be the single most dangerous thing
 *       this feature could do: a user re-importing their supplier's price list with a stock
 *       column would believe they had just corrected their inventory. It is a WARNING with a
 *       pointer to bulk stock-in - never silence, and deliberately not an error, because the
 *       row's other twelve columns are perfectly good and blocking them would punish the user
 *       for a column they did not think about.</li>
 *   <li><b>{@code unit_of_measure} is immutable once the product has any movement</b> (8.12,
 *       MULTI_VENDOR_INVENTORY_DESIGN.md 5.3). An ERROR on that cell with the reason spelled
 *       out, not a silent ignore - because the numbers already in the ledger are denominated in
 *       the old unit and changing it retroactively rewrites history.</li>
 *   <li><b>{@code cost_price} is not directly settable on an update.</b> It is the weighted
 *       average, owned by {@code stockIn}. A {@code cost_price} column on an update row updates
 *       the *vendor line's* {@code lastCostPrice}, which is the thing the user actually meant -
 *       and when the row names no supplier there is no vendor line to put it on, so that gets
 *       said too.</li>
 * </ol>
 *
 * <h2>Opening balance is only ever available on a row that creates a product</h2>
 * Design 6.7 asks for those words exactly. A created row's {@code quantity_on_hand} writes a
 * real {@code IN} movement through {@code StockManagementService.stockIn} - a genuine lot with a
 * vendor and a cost, converging on the same ledger bulk stock-in uses - and an updated row's
 * does nothing at all. So there is exactly one way to add stock to a product that already
 * exists, and nobody ever has to work out which of two tools moves a number.
 */
@Component
@RequiredArgsConstructor
public class ProductCatalogRowHandler implements ImportRowHandler {

    private static final String CACHE_PRODUCTS_BY_SKU = "catalog-products-by-sku";
    private static final String CACHE_MOVEMENT_EXISTS = "catalog-product-has-movements";
    private static final String CACHE_IS_SELLER = "catalog-tenant-is-seller";

    /** The note on the ledger row an opening balance writes. Matches the pre-V20 wording exactly. */
    private static final String OPENING_BALANCE_NOTE = "Opening balance (imported)";

    private final ProductRepository productRepository;
    private final ProductVendorRepository productVendorRepository;
    private final com.procurepal_services.stock_bridge_api.repository.ImportSessionRowRepository importSessionRowRepository;
    private final StockMovementRepository stockMovementRepository;
    private final StockMovementAllocationRepository stockMovementAllocationRepository;
    private final StockManagementService stockManagementService;
    private final ProductVendorService productVendorService;
    private final SellerDirectory sellerDirectory;
    private final VendorDirectory vendorDirectory;

    @Override
    public ImportKind kind() {
        return ImportKind.PRODUCT_CATALOG;
    }

    // ------------------------------------------------------------------ fields

    /**
     * Thirteen columns, in template order, or twelve for a buying company.
     *
     * <p>{@code unit_price} is omitted entirely for a non-seller, exactly as
     * {@code ProductExcelService.headerNamesFor} already decides for the downloadable template
     * (contract section 5: "unit_price is omitted entirely for a non-seller tenant, as today").
     * The two answers have to agree or a buying company gets a grid column its own template
     * never contained.
     *
     * <p>Reads the tenant through {@code TenantContext}, which the SPI's javadoc explicitly
     * permits and which is the only way a no-argument method can be tenant-correct.
     */
    @Override
    public List<ImportFieldDescriptor> fields() {
        boolean seller = isSeller(com.procurepal_services.stock_bridge_api.tenant.TenantContext.get());
        List<ImportFieldDescriptor> fields = new ArrayList<>();
        fields.add(ImportFieldDescriptor.of(ImportFields.NAME, "Product name", ImportFieldDescriptor.Type.TEXT, true,
                "What you call this product."));
        fields.add(ImportFieldDescriptor.of(ImportFields.SKU, "Product code", ImportFieldDescriptor.Type.TEXT, true,
                "Your own code for this product. It has to be unique in your catalog."));
        fields.add(ImportFieldDescriptor.text(ImportFields.DESCRIPTION, "Description",
                "Anything you want shown on the product page."));
        if (seller) {
            fields.add(ImportFieldDescriptor.of(ImportFields.UNIT_PRICE, "Selling price",
                    ImportFieldDescriptor.Type.MONEY, true, "Your marketplace selling price."));
        }
        fields.add(ImportFieldDescriptor.of(ImportFields.COST_PRICE, "Cost price",
                ImportFieldDescriptor.Type.MONEY, false,
                "What you pay for one unit. On a new product with a quantity, this becomes that stock's cost."));
        fields.add(ImportFieldDescriptor.of(ImportFields.QUANTITY_ON_HAND, "Opening stock",
                ImportFieldDescriptor.Type.INTEGER, false,
                "How much you have right now. Only used on products we are creating - it is recorded as an "
                        + "opening stock entry."));
        fields.add(ImportFieldDescriptor.of(ImportFields.LOW_STOCK_THRESHOLD, "Low stock level",
                ImportFieldDescriptor.Type.INTEGER, false, "We warn you when stock falls to this number."));
        fields.add(ImportFieldDescriptor.enumeration(ImportFields.UNIT_OF_MEASURE, "Unit of measure", false,
                "What one unit of this product is measured in.", RowValues.options(UnitOfMeasure.baseUnits())));
        fields.add(ImportFieldDescriptor.enumeration(ImportFields.PACKAGING_UNIT, "Packaging", false,
                "How it is packaged - Bag, Carton, Drum. Leave blank if it is sold loose.",
                RowValues.options(UnitOfMeasure.packagingUnits())));
        fields.add(ImportFieldDescriptor.of(ImportFields.PACKAGING_SIZE, "Units per pack",
                ImportFieldDescriptor.Type.NUMBER, false,
                "How many units are in one package. Kilogram + Bag + 50 means a 50kg bag."));
        fields.add(new ImportFieldDescriptor(ImportFields.VENDOR_NAME, "Supplier",
                ImportFieldDescriptor.Type.REFERENCE, false, false, false,
                "Who you buy this from. Repeat the product code on the next row to add a second supplier.", null));
        fields.add(ImportFieldDescriptor.text(ImportFields.VENDOR_SKU, "Supplier's code",
                "That supplier's own code for this product, if it differs from yours."));
        fields.add(ImportFieldDescriptor.of(ImportFields.IS_PREFERRED_VENDOR, "Main supplier",
                ImportFieldDescriptor.Type.BOOLEAN, false,
                "TRUE if this is your main supplier for the product. Only one supplier per product can be."));
        return fields;
    }

    // ---------------------------------------------------------------- validate

    @Override
    public RowValidation validate(RowContext ctx) {
        RowValidation.Builder out = RowValidation.builder();
        boolean seller = isSeller(ctx.tenantId());

        String name = ctx.text(ImportFields.NAME);
        String sku = ctx.text(ImportFields.SKU);
        String subject = ImportCopy.subject(name, sku);
        out.value(ImportFields.NAME, name);
        out.value(ImportFields.SKU, sku);

        Product existing = sku == null ? null : findBySku(ctx.tenantId(), ctx.cache(), sku);
        // The continuation convention (design 7.1) means a second row for the same product
        // legitimately carries nothing but supplier columns - no name, no price, no unit. It
        // cannot be recognised from one row, so validateBatch decides it; what this row can do
        // is notice that it looks like one, and hold back the "every product needs a name"
        // complaint that would otherwise be retracted a moment later. Retracting an error is
        // fine internally, but the required-field checks below would have already poisoned the
        // normalized map with nulls the parent row's values should fill.
        boolean looksLikeContinuation = name == null && sku != null && ctx.has(ImportFields.VENDOR_NAME);

        if (sku == null) {
            out.error(ImportFields.SKU, "SKU_REQUIRED",
                    "Every product needs its own code so we can tell it apart from the rest of your catalog. "
                            + "Row " + ctx.excelRow() + " has none.");
        }
        if (name == null && !looksLikeContinuation) {
            out.error(ImportFields.NAME, "NAME_REQUIRED",
                    "Every product needs a name - what should we call " + ImportCopy.quote(sku == null ? "this row" : sku) + "?");
        }

        out.value(ImportFields.DESCRIPTION, ctx.text(ImportFields.DESCRIPTION));

        // --- mode (design 6.3). Decided before the columns are read, because whether a column
        // is even looked at depends on whether this row creates or updates.
        boolean creating = resolveMode(ctx, out, existing, subject, looksLikeContinuation);

        if (seller && creating && !looksLikeContinuation) {
            BigDecimal unitPrice = RowValues.money(ctx, out, ImportFields.UNIT_PRICE, "Selling price", subject);
            if (unitPrice == null && ctx.text(ImportFields.UNIT_PRICE) == null) {
                out.error(ImportFields.UNIT_PRICE, "UNIT_PRICE_REQUIRED",
                        "Every product you list needs a selling price. What are you selling " + subject + " for?");
            }
        } else {
            RowValues.money(ctx, out, ImportFields.UNIT_PRICE, "Selling price", subject);
        }

        BigDecimal costPrice = RowValues.money(ctx, out, ImportFields.COST_PRICE, "Cost price", subject);
        Integer quantity = RowValues.wholeNumber(ctx, out, ImportFields.QUANTITY_ON_HAND, "Opening stock", subject);
        RowValues.wholeNumber(ctx, out, ImportFields.LOW_STOCK_THRESHOLD, "Low stock level", subject);

        String unitOfMeasure = RowValues.unitCode(ctx, out, ImportFields.UNIT_OF_MEASURE,
                UnitOfMeasureRole.BASE, "Unit of measure", "packaging", subject);
        String packagingUnit = RowValues.unitCode(ctx, out, ImportFields.PACKAGING_UNIT,
                UnitOfMeasureRole.PACKAGING, "Packaging", "unit of measure", subject);
        BigDecimal packagingSize =
                RowValues.decimal(ctx, out, ImportFields.PACKAGING_SIZE, "Units per pack", subject);

        validatePackagingCoherence(ctx, out, subject, unitOfMeasure, packagingUnit, packagingSize);
        validateVendorColumns(ctx, out, subject, costPrice, creating);

        if (!creating && existing != null) {
            applyUpdateRules(ctx, out, existing, subject, quantity, unitOfMeasure, costPrice);
            out.resolvedTo(existing.getId(), existing.getName());
        }

        return out.build();
    }

    /**
     * The mode table from design 6.3, and the one place a SKU collision stops being a hard error.
     *
     * <pre>
     *   CREATE_ONLY       existing -> error on that row      new -> create
     *   CREATE_OR_UPDATE  existing -> update changed fields  new -> create
     *   UPDATE_ONLY       existing -> update                 new -> error on that row
     * </pre>
     *
     * <p>Today's behaviour is the top-left cell, and it is what makes "re-upload my supplier's
     * updated price list" - the most natural recurring workflow this feature has - impossible.
     * The decision is made once, explicitly, at upload, rather than guessed per row.
     *
     * @return true when this row will create a product.
     */
    private boolean resolveMode(
            RowContext ctx, RowValidation.Builder out, Product existing, String subject, boolean looksLikeContinuation) {
        ImportMode mode = ctx.mode();
        if (existing != null) {
            if (mode == ImportMode.CREATE_ONLY && !looksLikeContinuation) {
                out.error(ImportFields.SKU, "SKU_EXISTS",
                        "You already stock %s under this code. This import is set to add new products only - "
                                .formatted(existing.getName())
                                + "switch it to “Add or update” at the top of the page to update it instead.");
            }
            return false;
        }
        if (mode == ImportMode.UPDATE_ONLY) {
            out.error(ImportFields.SKU, "SKU_NOT_FOUND",
                    "There is no product in your catalog with this code, and this import is set to update "
                            + "existing products only. Switch it to “Add or update” to create " + subject + ".");
        }
        return true;
    }

    /**
     * Packaging coherence, mirroring the manual path's three rules exactly
     * ({@code requirePackagingUnitAndSizePaired}, {@code requirePackagingImpliesUnitOfMeasure}).
     *
     * <p>Presence is tested from the raw cell rather than the resolved value, so an unreadable
     * packaging code does not ALSO produce a misleading "requires a unit of measure" complaint
     * against a column that was in fact filled in correctly. Two errors for one mistake is how a
     * review screen loses a user's trust.
     */
    private void validatePackagingCoherence(
            RowContext ctx,
            RowValidation.Builder out,
            String subject,
            String unitOfMeasure,
            String packagingUnit,
            BigDecimal packagingSize) {
        boolean unitProvided = ctx.has(ImportFields.UNIT_OF_MEASURE);
        boolean packagingUnitProvided = ctx.has(ImportFields.PACKAGING_UNIT);
        boolean packagingSizeProvided = ctx.has(ImportFields.PACKAGING_SIZE);

        if (packagingUnitProvided != packagingSizeProvided) {
            if (packagingUnitProvided) {
                out.error(ImportFields.PACKAGING_SIZE, "PACKAGING_SIZE_REQUIRED",
                        "How many units are in one %s of %s? Packaging needs a size to be useful."
                                .formatted(ImportCopy.unitLabel(packagingUnit == null ? "pack" : packagingUnit), subject));
            } else {
                out.error(ImportFields.PACKAGING_UNIT, "PACKAGING_UNIT_REQUIRED",
                        "%s says there are %s units in a pack, but not what kind of pack. Bag, carton, drum?"
                                .formatted(subject, ImportCopy.count(packagingSize)));
            }
        }
        if (!unitProvided && (packagingUnitProvided || packagingSizeProvided)) {
            out.error(ImportFields.UNIT_OF_MEASURE, "UNIT_REQUIRED_FOR_PACKAGING",
                    "Before we can record how %s is packaged, we need to know what one unit of it is measured in."
                            .formatted(subject));
        }
    }

    /**
     * The vendor trio. {@code vendor_sku} and {@code is_preferred_vendor} hang off
     * {@code vendor_name}: a supplier's code, or a "this is my main supplier" flag, with no
     * supplier named is not a partly-filled row, it is a row whose author meant something we
     * cannot guess.
     *
     * <p>An unmatched supplier name is a WARNING and not an error, on purpose. It is a question
     * for the resolution card above the grid (design 6.4), and the frontend's Continue button is
     * gated on {@code errorCount === 0} - so making it an error would mean an unanswered
     * supplier question blocks the whole import, when the honest answer is "we will import these
     * rows without a supplier unless you tell us otherwise". Saying that out loud is what keeps
     * it from being a silent drop.
     */
    private void validateVendorColumns(
            RowContext ctx, RowValidation.Builder out, String subject, BigDecimal costPrice, boolean creating) {
        String vendorName = ctx.text(ImportFields.VENDOR_NAME);
        String vendorSku = ctx.text(ImportFields.VENDOR_SKU);
        out.value(ImportFields.VENDOR_NAME, vendorName);
        out.value(ImportFields.VENDOR_SKU, vendorSku);
        Boolean preferred = RowValues.flag(ctx, out, ImportFields.IS_PREFERRED_VENDOR, "Main supplier", subject);

        if (vendorName == null) {
            if (vendorSku != null) {
                out.error(ImportFields.VENDOR_SKU, "VENDOR_SKU_NEEDS_VENDOR",
                        "This row gives a supplier's own code for %s but does not say which supplier.".formatted(subject));
            }
            if (Boolean.TRUE.equals(preferred)) {
                out.error(ImportFields.IS_PREFERRED_VENDOR, "PREFERRED_NEEDS_VENDOR",
                        "This row marks a main supplier for %s but does not name one.".formatted(subject));
            }
            return;
        }

        Optional<ValueResolution> resolution = ctx.resolution(ImportFields.VENDOR_NAME);
        if (resolution.isPresent()) {
            return;
        }
        if (vendorDirectory.match(ctx.tenantId(), ctx.cache(), vendorName) == null) {
            out.warning(ImportFields.VENDOR_NAME, "VENDOR_UNKNOWN",
                    "%s is not one of your suppliers yet. Answer the supplier question above and we will link "
                            .formatted(ImportCopy.quote(vendorName))
                            + "every row that names them - otherwise " + subject + " is imported without a supplier.");
        }
    }

    /** Design 6.3's three update rules. See the class javadoc for why each one is loud. */
    private void applyUpdateRules(
            RowContext ctx,
            RowValidation.Builder out,
            Product existing,
            String subject,
            Integer quantity,
            String unitOfMeasure,
            BigDecimal costPrice) {

        // (1) Quantity is ignored, and the grid says so. Contract section 8.8 - never silently.
        // The message is contract section 4's, verbatim, because the frontend renders it as-is
        // and the mock the review screen was built against contains this exact sentence.
        if (quantity != null && quantity > 0) {
            out.warning(ImportFields.QUANTITY_ON_HAND, "QUANTITY_IGNORED_ON_UPDATE",
                    "Quantity is ignored when updating an existing product — use Record stock you received.");
        }

        // (2) unit_of_measure is immutable once the product has any movement. Contract 8.12.
        if (unitOfMeasure != null
                && !Objects.equals(unitOfMeasure, existing.getUnitOfMeasure())
                && hasMovements(ctx.tenantId(), ctx.cache(), existing.getId())) {
            String current = ImportCopy.unitLabel(existing.getUnitOfMeasure());
            out.error(ImportFields.UNIT_OF_MEASURE, "UNIT_IMMUTABLE",
                    "%s has already had stock recorded in %s, so we cannot change what it is measured in - "
                            .formatted(existing.getName(), current == null ? "its current unit" : current)
                            + "everything already in the ledger is counted that way. Add a separate product if you "
                            + "now buy it in " + ImportCopy.unitLabel(unitOfMeasure) + ".");
        }

        // (3) cost_price is the weighted average and belongs to stockIn. On an update row it
        // sets the vendor line's lastCostPrice instead - which is the thing the user meant - and
        // there is nowhere to put it when the row names no supplier.
        if (costPrice != null && ctx.text(ImportFields.VENDOR_NAME) == null) {
            out.warning(ImportFields.COST_PRICE, "COST_PRICE_NEEDS_VENDOR",
                    "%s already has a cost worked out from what you have actually paid, so this column only ".formatted(subject)
                            + "updates a supplier's price. Name the supplier on this row and we will update theirs.");
        }
    }

    // ----------------------------------------------------------- validateBatch

    /**
     * Duplicate SKUs, continuation rows and the preferred-supplier flag - the three rules no
     * single row can decide.
     *
     * <h2>Design 7.1's convention, and the error it narrows</h2>
     * "Multiple vendors for one product = repeat the SKU on consecutive rows, with only the
     * vendor columns filled on the continuation rows." This is Odoo's one-to-many import
     * convention exactly, and it means the flat "duplicate SKU within file" error M2's parser
     * still raises has to be narrowed here: a repeated SKU naming a *different* supplier is a
     * second vendor line, and a repeated SKU with the same supplier, or none, stays an error.
     *
     * <p>The discriminator is supplier identity rather than physical row adjacency. The spec
     * describes the convention as consecutive rows because that is how people write them, but it
     * states the *rule* in terms of the supplier - and a file whose rows for one product are
     * separated by a blank line the reader dropped, or sorted by supplier rather than by
     * product, is still unambiguous. Requiring adjacency would reject a legible file on a
     * technicality; requiring a distinct supplier rejects exactly the ambiguous case and nothing
     * else.
     *
     * <p>Continuation rows carrying non-supplier values get a warning rather than silence. The
     * parent row owns the product's details, so a name or price on a continuation row is not
     * read - and a user who typed one needs to be told, or they will believe they changed
     * something.
     *
     * <h2>Multiple main suppliers</h2>
     * An error, matching {@code uq_product_vendors_preferred}, the partial unique index. Left to
     * the database it would surface as a constraint violation half way through the commit
     * transaction, which rolls back the whole import and names a constraint rather than a row.
     */
    @Override
    public void validateBatch(BatchContext ctx) {
        Map<String, List<ImportRowState>> bySku = new LinkedHashMap<>();
        for (ImportRowState state : ctx.states()) {
            String sku = state.text(ImportFields.SKU);
            if (sku == null) {
                continue;
            }
            bySku.computeIfAbsent(sku.toUpperCase(Locale.ROOT), key -> new ArrayList<>()).add(state);
        }

        for (List<ImportRowState> group : bySku.values()) {
            if (group.size() == 1) {
                continue;
            }
            ImportRowState parent = group.get(0);
            Set<String> vendorsSeen = new LinkedHashSet<>();
            String parentVendor = foldedVendor(parent);
            if (parentVendor != null) {
                vendorsSeen.add(parentVendor);
            }
            boolean preferredClaimed = Boolean.TRUE.equals(parent.value(ImportFields.IS_PREFERRED_VENDOR));

            for (ImportRowState state : group.subList(1, group.size())) {
                String vendor = foldedVendor(state);
                if (vendor == null || !vendorsSeen.add(vendor)) {
                    state.addError(RowIssue.error(ImportFields.SKU, "DUPLICATE_SKU",
                            "This row repeats the code from row %d. If it is the same product from a second supplier, "
                                    .formatted(parent.excelRow())
                                    + "name that supplier; otherwise it is a duplicate and we would create the same "
                                    + "product twice."));
                    continue;
                }

                // A genuine second supplier. Retract the required-field complaints validate()
                // raised for the columns this row is not expected to carry - the parent row owns
                // them - and nest it in the grid.
                state.setContinuationOf(parent.excelRow());
                state.clearErrors(ImportFields.NAME, "NAME_REQUIRED");
                state.clearErrors(ImportFields.UNIT_PRICE, "UNIT_PRICE_REQUIRED");
                state.clearErrors(ImportFields.SKU, "SKU_EXISTS");
                state.clearErrors(ImportFields.SKU, "SKU_NOT_FOUND");
                warnAboutIgnoredColumns(state, parent);

                if (Boolean.TRUE.equals(state.value(ImportFields.IS_PREFERRED_VENDOR))) {
                    if (preferredClaimed) {
                        state.addError(RowIssue.error(ImportFields.IS_PREFERRED_VENDOR, "MULTIPLE_PREFERRED",
                                "A product can only have one main supplier, and row %d already names one for this product."
                                        .formatted(parent.excelRow())));
                    } else {
                        preferredClaimed = true;
                    }
                }
            }
        }
    }

    private void warnAboutIgnoredColumns(ImportRowState state, ImportRowState parent) {
        List<String> ignorable = List.of(
                ImportFields.NAME, ImportFields.DESCRIPTION, ImportFields.UNIT_PRICE,
                ImportFields.QUANTITY_ON_HAND, ImportFields.LOW_STOCK_THRESHOLD,
                ImportFields.UNIT_OF_MEASURE, ImportFields.PACKAGING_UNIT, ImportFields.PACKAGING_SIZE);
        for (String field : ignorable) {
            if (state.value(field) != null) {
                state.addWarning(RowIssue.warning(field, "CONTINUATION_COLUMN_IGNORED",
                        "Only the supplier columns are read on this row - the product's own details come from row %d."
                                .formatted(parent.excelRow())));
                return;
            }
        }
    }

    private String foldedVendor(ImportRowState state) {
        return ValueMappings.normalizeKey(state.text(ImportFields.VENDOR_NAME));
    }

    // -------------------------------------------------------- unresolvedValues

    /**
     * Supplier names nothing matches, collapsed to one question each.
     *
     * <p>Only suppliers. Unit codes are deliberately NOT surfaced here even though design 6.4
     * lists them, because M2's forgiving parse already absorbs almost all of them and whatever
     * survives is answered better as a cell error carrying a suggestion and a
     * {@code bulkFixCount} - which is contract section 4's {@code [Fix all 12 "KGS" rows]}
     * affordance, the same one decision applied to the same twelve rows. Asking the same
     * question in two places on one screen is worse than asking it well in one.
     */
    @Override
    public List<UnresolvedValue> unresolvedValues(BatchContext ctx) {
        boolean mayCreate = hasAuthority("MANAGE_VENDORS");
        Map<String, List<Integer>> rowsByValue = new LinkedHashMap<>();
        Map<String, String> originalSpelling = new LinkedHashMap<>();

        for (ImportRowState state : ctx.states()) {
            if (state.isSkipped()) {
                continue;
            }
            String vendorName = state.text(ImportFields.VENDOR_NAME);
            if (vendorName == null) {
                continue;
            }
            String folded = ValueMappings.normalizeKey(vendorName);
            if (vendorDirectory.match(ctx.tenantId(), ctx.cache(), vendorName) != null) {
                continue;
            }
            originalSpelling.putIfAbsent(folded, vendorName);
            rowsByValue.computeIfAbsent(folded, key -> new ArrayList<>()).add(state.excelRow());
        }

        List<UnresolvedValue> unresolved = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> entry : rowsByValue.entrySet()) {
            String value = originalSpelling.get(entry.getKey());
            unresolved.add(new UnresolvedValue(
                    ImportFields.VENDOR_NAME,
                    "Supplier",
                    value,
                    entry.getValue().size(),
                    // Capped: a name on four thousand rows does not need four thousand numbers
                    // on the wire, and the card only ever shows the first handful.
                    entry.getValue().stream().limit(50).toList(),
                    UnresolvedValue.Kind.VENDOR,
                    vendorDirectory.suggestionsFor(ctx.tenantId(), ctx.cache(), value),
                    mayCreate,
                    true,
                    false,
                    ctx.valueMappings().resolutionFor(ImportFields.VENDOR_NAME, value).orElse(null)));
        }
        return unresolved;
    }

    // ---------------------------------------------------------------- preview

    @Override
    public CommitPreview preview(BatchContext ctx) {
        Tally tally = tally(ctx);
        int total = tally.creates + tally.updates;

        CommitPreview.Builder preview = CommitPreview.builder()
                .headline("Import %s from %s".formatted(ImportCopy.rows(total), ctx.session().getOriginalFilename()))
                .confirmLabel("Import " + ImportCopy.rows(total))
                // Worded from design 9.4's mock, which is also contract section 4's example.
                .line("create", "Create", tally.creates,
                        ImportCopy.qualified(tally.creates, "new", "product", "products"))
                .line("update", "Update", tally.updates,
                        ImportCopy.qualified(tally.updates, "existing", "product", "products"))
                .line("skip", "Skip", tally.skipped, ImportCopy.rows(tally.skipped) + " you marked as skipped")
                .line("vendors", "Suppliers", tally.vendorsToCreate,
                        ImportCopy.qualified(tally.vendorsToCreate, "new", "supplier", "suppliers")
                                + " will be added to your directory");

        // The Stock line exists because design 3's fix means an import now touches the ledger,
        // and design 9.4 is explicit that a user must not discover that afterwards.
        if (tally.openingProducts > 0) {
            preview.always("stock", "Stock", tally.openingProducts,
                    "Opening balance of %s recorded across %s"
                            .formatted(quantityPhrase(tally), ImportCopy.products(tally.openingProducts)));
        }
        if (total == 0) {
            preview.blockedReason("There is nothing left to import - every row is either skipped or still has "
                    + "something to fix.");
        }
        return preview.build();
    }

    private String quantityPhrase(Tally tally) {
        // One unit across the whole batch reads naturally ("3,400 kg"); a mix does not, and
        // inventing a total across kilograms and cartons would be a lie, so it falls back to
        // plain units.
        if (tally.openingUnits.size() == 1) {
            String unit = tally.openingUnits.iterator().next();
            return ImportCopy.count(tally.openingQuantity) + " " + ImportCopy.unitSymbol(unit);
        }
        return ImportCopy.count(tally.openingQuantity) + " units";
    }

    private static final class Tally {
        int creates;
        int updates;
        int skipped;
        int vendorsToCreate;
        int openingProducts;
        long openingQuantity;
        final Set<String> openingUnits = new LinkedHashSet<>();
    }

    private Tally tally(BatchContext ctx) {
        Tally tally = new Tally();
        for (ImportRowState state : ctx.states()) {
            if (state.isSkipped()) {
                tally.skipped++;
                continue;
            }
            if (!state.isCommittable() || state.getContinuationOf() != null) {
                continue;
            }
            if (state.getResolvedEntityId() == null) {
                tally.creates++;
                Object quantity = state.value(ImportFields.QUANTITY_ON_HAND);
                if (quantity instanceof Number number && number.intValue() > 0) {
                    tally.openingProducts++;
                    tally.openingQuantity += number.intValue();
                    String unit = state.text(ImportFields.UNIT_OF_MEASURE);
                    tally.openingUnits.add(unit == null ? "unit" : unit);
                }
            } else {
                tally.updates++;
            }
        }
        tally.vendorsToCreate = (int) distinctCreateNewVendors(ctx).size();
        return tally;
    }

    private Map<String, ValueResolution> distinctCreateNewVendors(BatchContext ctx) {
        Map<String, ValueResolution> wanted = new LinkedHashMap<>();
        for (ImportRowState state : ctx.states()) {
            if (!state.isCommittable()) {
                continue;
            }
            String vendorName = state.text(ImportFields.VENDOR_NAME);
            if (vendorName == null) {
                continue;
            }
            ctx.valueMappings()
                    .resolutionFor(ImportFields.VENDOR_NAME, vendorName)
                    .filter(ValueResolution::isCreateNew)
                    .ifPresent(resolution -> wanted.putIfAbsent(ValueMappings.normalizeKey(vendorName), resolution));
        }
        return wanted;
    }

    // ----------------------------------------------------------------- commit

    /**
     * One transaction, all-or-nothing, every written row stamped (design 6.5).
     *
     * <p>Order is load-bearing. Suppliers are created first because a product's vendor line
     * needs one; products next; vendor lines after that, so a continuation row's supplier
     * attaches to a product that exists; opening balances last, because
     * {@code StockManagementService.stockIn} refuses a receipt with no supplier on a product that
     * already has vendor lines, and those lines have just been written.
     *
     * <p>Nothing here reimplements stock logic. Design 8.2's instruction - "each valid row is a
     * {@code StockInRequest} against {@code StockManagementService.stockIn()}... do not
     * reimplement any of it" - applies to the opening balance exactly as it does to bulk
     * stock-in, so unit conversion, the weighted-average cost and the cached rollups are all the
     * existing code's.
     */
    @Override
    public CommitOutcome commit(BatchContext ctx) {
        UUID batchId = ctx.session().getId();
        Map<String, CompanyVendor> createdVendors = createResolvedVendors(ctx);

        int created = 0;
        int updated = 0;
        int skipped = 0;
        int movements = 0;

        Map<Integer, Product> productsByExcelRow = new LinkedHashMap<>();

        for (ImportRowState state : ctx.states()) {
            if (state.isSkipped()) {
                state.setOutcome(ImportFields.OUTCOME_SKIPPED);
                state.setOutcomeMessage("You marked this row as skipped.");
                skipped++;
                continue;
            }
            if (!state.isCommittable()) {
                // Unreachable on a normal commit - the engine refuses to start one while any row
                // still has a blocking error - but recorded rather than assumed away, so the
                // report of a run that somehow got here is still complete.
                state.setOutcome(ImportFields.OUTCOME_FAILED);
                state.setOutcomeMessage("This row still had something to fix.");
                continue;
            }
            if (state.getContinuationOf() != null) {
                // Folded into its parent; the vendor pass below picks it up.
                state.setOutcome(ImportFields.OUTCOME_SKIPPED);
                state.setOutcomeMessage("Added as a second supplier for the product on row %d."
                        .formatted(state.getContinuationOf()));
                skipped++;
                continue;
            }

            if (state.getResolvedEntityId() == null) {
                Product product = createProduct(ctx, state, batchId);
                productsByExcelRow.put(state.excelRow(), product);
                state.setOutcome(ImportFields.OUTCOME_CREATED);
                state.setOutcomeMessage("Created.");
                created++;
            } else {
                Product product = productRepository
                        .findByIdAndClientId(state.getResolvedEntityId(), ctx.tenantId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Row " + state.excelRow() + " resolved to a product that is no longer there"));
                snapshotBeforeUpdate(state, product);
                updateProduct(ctx, state, product);
                productsByExcelRow.put(state.excelRow(), product);
                state.setOutcome(ImportFields.OUTCOME_UPDATED);
                state.setOutcomeMessage("Updated.");
                updated++;
            }
        }
        productRepository.flush();

        for (ImportRowState state : ctx.states()) {
            if (!state.isCommittable() || state.isSkipped()) {
                continue;
            }
            Integer parentRow = state.getContinuationOf();
            Product product = productsByExcelRow.get(parentRow == null ? state.excelRow() : parentRow);
            if (product != null) {
                applyVendorLine(ctx, state, product, createdVendors);
            }
        }

        for (ImportRowState state : ctx.states()) {
            if (!state.isCommittable() || state.isSkipped() || state.getContinuationOf() != null) {
                continue;
            }
            if (!ImportFields.OUTCOME_CREATED.equals(state.getOutcome())) {
                continue;
            }
            Product product = productsByExcelRow.get(state.excelRow());
            if (recordOpeningBalance(ctx, state, product, createdVendors, batchId)) {
                movements++;
            }
        }

        int total = created + updated;
        List<CommitPreview.Line> lines = new ArrayList<>();
        if (created > 0) {
            lines.add(new CommitPreview.Line("create", "Created", created, ImportCopy.products(created) + " created"));
        }
        if (updated > 0) {
            lines.add(new CommitPreview.Line("update", "Updated", updated, ImportCopy.products(updated) + " updated"));
        }
        if (skipped > 0) {
            lines.add(new CommitPreview.Line("skip", "Skipped", skipped, ImportCopy.rows(skipped) + " skipped"));
        }
        if (!createdVendors.isEmpty()) {
            lines.add(new CommitPreview.Line("vendors", "Suppliers", createdVendors.size(),
                    ImportCopy.suppliers(createdVendors.size()) + " added to your directory"));
        }
        if (movements > 0) {
            lines.add(new CommitPreview.Line("stock", "Stock", movements,
                    "Opening stock recorded for " + ImportCopy.products(movements)));
        }
        return new CommitOutcome(
                "Imported " + ImportCopy.rows(total), lines, created, updated, skipped, 0,
                createdVendors.size(), 0, movements);
    }

    private Map<String, CompanyVendor> createResolvedVendors(BatchContext ctx) {
        Map<String, CompanyVendor> created = new LinkedHashMap<>();
        for (Map.Entry<String, ValueResolution> entry : distinctCreateNewVendors(ctx).entrySet()) {
            String name = entry.getValue().payloadText("name");
            if (name == null) {
                continue;
            }
            CompanyVendor vendor = vendorDirectory.createInline(name);
            created.put(entry.getKey(), vendor);
            // The pass cache said "no such supplier" a moment ago and would keep saying it.
            ctx.cache().invalidate("vendors-by-name", ctx.tenantId());
        }
        return created;
    }

    private Product createProduct(BatchContext ctx, ImportRowState state, UUID batchId) {
        Client owner = sellerDirectory.findSellerOfRecord(ctx.tenantId()).orElse(null);
        boolean seller = owner != null && owner.canSell();
        return productRepository.save(Product.builder()
                .name(state.text(ImportFields.NAME))
                .sku(state.text(ImportFields.SKU))
                .description(state.text(ImportFields.DESCRIPTION))
                .unitPrice(seller ? decimalOf(state, ImportFields.UNIT_PRICE) : null)
                .costPrice(decimalOf(state, ImportFields.COST_PRICE))
                // Zero, always. Contract section 8.1: no quantity reaches quantity_on_hand
                // without a StockMovement, and the opening-balance stockIn below is the only
                // thing allowed to move it.
                .quantityOnHand(0)
                .incomingQuantity(0)
                .lowStockThreshold(intOf(state, ImportFields.LOW_STOCK_THRESHOLD))
                .unitOfMeasure(state.text(ImportFields.UNIT_OF_MEASURE))
                .packagingUnit(state.text(ImportFields.PACKAGING_UNIT))
                .packagingSize(decimalOf(state, ImportFields.PACKAGING_SIZE))
                .active(true)
                .approvalStatus(ProductModerationRules.initialStatusFor(owner))
                .importBatchId(batchId)
                .build());
    }

    /**
     * The pre-update snapshot undo reads back.
     *
     * <p>M1's note names the gap this fills: {@code products.import_batch_id} is set on CREATE
     * only, so "created by this batch" is a column but "touched by this batch" is not, and an
     * update's undo has to consult the row snapshot for the second set. The snapshot is of the
     * *entity before the change*, not of the file - the file is what it was changed to.
     *
     * <p>Only the fields this handler is capable of changing are captured. Snapshotting the whole
     * product would make an undo silently revert something a different feature changed in the
     * meantime, which is a far worse outcome than an undo that reverts slightly less.
     */
    private void snapshotBeforeUpdate(ImportRowState state, Product product) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put(ImportFields.NAME, product.getName());
        before.put(ImportFields.DESCRIPTION, product.getDescription());
        before.put(ImportFields.UNIT_PRICE, product.getUnitPrice() == null ? null : product.getUnitPrice().toPlainString());
        before.put(ImportFields.LOW_STOCK_THRESHOLD, product.getLowStockThreshold());
        before.put(ImportFields.UNIT_OF_MEASURE, product.getUnitOfMeasure());
        before.put(ImportFields.PACKAGING_UNIT, product.getPackagingUnit());
        before.put(ImportFields.PACKAGING_SIZE,
                product.getPackagingSize() == null ? null : product.getPackagingSize().toPlainString());
        state.getNormalized().put(ImportFields.BEFORE, before);
    }

    /**
     * Applies only the columns the file actually carried. A blank cell on an update row means
     * "I did not say anything about this", not "set it to nothing" - a supplier's price list
     * with no description column must not wipe every description in the catalog.
     *
     * <p>{@code quantityOnHand} and {@code costPrice} are conspicuously absent. See the class
     * javadoc.
     */
    private void updateProduct(BatchContext ctx, ImportRowState state, Product product) {
        Client owner = sellerDirectory.findSellerOfRecord(ctx.tenantId()).orElse(null);
        boolean seller = owner != null && owner.canSell();

        if (state.text(ImportFields.NAME) != null) {
            product.setName(state.text(ImportFields.NAME));
        }
        if (state.text(ImportFields.DESCRIPTION) != null) {
            product.setDescription(state.text(ImportFields.DESCRIPTION));
        }
        if (seller && decimalOf(state, ImportFields.UNIT_PRICE) != null) {
            product.setUnitPrice(decimalOf(state, ImportFields.UNIT_PRICE));
        }
        if (intOf(state, ImportFields.LOW_STOCK_THRESHOLD) != null) {
            product.setLowStockThreshold(intOf(state, ImportFields.LOW_STOCK_THRESHOLD));
        }
        if (state.text(ImportFields.UNIT_OF_MEASURE) != null) {
            product.setUnitOfMeasure(state.text(ImportFields.UNIT_OF_MEASURE));
        }
        if (state.text(ImportFields.PACKAGING_UNIT) != null) {
            product.setPackagingUnit(state.text(ImportFields.PACKAGING_UNIT));
        }
        if (decimalOf(state, ImportFields.PACKAGING_SIZE) != null) {
            product.setPackagingSize(decimalOf(state, ImportFields.PACKAGING_SIZE));
        }
    }

    /**
     * Creates or updates the {@code ProductVendor} line this row names.
     *
     * <p>Written directly rather than through {@code ProductVendorService.findOrCreateForReceipt}
     * because that method is about a *receipt* and rolls a delivered quantity into the line's
     * cached rollups. A catalog row that names a supplier without a quantity is asserting a
     * relationship, not recording a delivery, and passing zero through the receipt path to get
     * the find-or-create would be using it for something it does not mean. Rows that DO carry an
     * opening balance still go through it, from {@link #recordOpeningBalance}.
     */
    private void applyVendorLine(
            BatchContext ctx, ImportRowState state, Product product, Map<String, CompanyVendor> createdVendors) {
        CompanyVendor vendor = resolveVendor(ctx, state, createdVendors);
        if (vendor == null) {
            return;
        }
        ProductVendor line = productVendorRepository
                .findByClientIdAndProductIdAndCompanyVendorId(ctx.tenantId(), product.getId(), vendor.getId())
                .orElse(null);
        if (line == null) {
            long existingLines = productVendorRepository.countByClientIdAndProductId(ctx.tenantId(), product.getId());
            line = ProductVendor.builder()
                    .product(product)
                    .companyVendor(vendor)
                    // First line on a product is automatically preferred - the same rule
                    // MULTI_VENDOR_INVENTORY_DESIGN.md 5.1/7.3 states and findOrCreateForReceipt
                    // already implements, restated here rather than diverged from.
                    .isPreferred(existingLines == 0)
                    .quantityOnHandFromVendor(0)
                    .totalQuantityReceived(0)
                    .build();
        }
        if (state.text(ImportFields.VENDOR_SKU) != null) {
            line.setVendorSku(state.text(ImportFields.VENDOR_SKU));
        }
        BigDecimal costPrice = decimalOf(state, ImportFields.COST_PRICE);
        if (costPrice != null) {
            line.setLastCostPrice(costPrice);
        }
        productVendorRepository.saveAndFlush(line);

        if (Boolean.TRUE.equals(state.value(ImportFields.IS_PREFERRED_VENDOR)) && !line.isPreferred()) {
            // Demote first, flush, then promote. uq_product_vendors_preferred is a partial
            // unique index and two preferred rows existing even momentarily inside the
            // transaction trips it.
            productVendorRepository
                    .findByClientIdAndProductIdAndIsPreferredTrue(ctx.tenantId(), product.getId())
                    .ifPresent(current -> {
                        current.setPreferred(false);
                        productVendorRepository.saveAndFlush(current);
                    });
            line.setPreferred(true);
            productVendorRepository.saveAndFlush(line);
        }
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
                return companyVendorById(ctx, answer.id());
            }
            if (answer.isLiteral()) {
                vendorName = answer.value();
            }
        }
        return vendorDirectory.match(ctx.tenantId(), ctx.cache(), vendorName);
    }

    private CompanyVendor companyVendorById(BatchContext ctx, UUID id) {
        return vendorDirectory.byFoldedName(ctx.tenantId(), ctx.cache()).values().stream()
                .filter(vendor -> vendor.getId().equals(id))
                .findFirst()
                .orElse(null);
    }

    /**
     * The opening balance - design 3's fix, and the reason a catalog import touches the ledger.
     *
     * <p>Only ever on a row that creates a product (design 6.7, stated in exactly those words).
     * Goes through {@code stockIn} rather than setting {@code quantityOnHand}, which is contract
     * section 8.1's non-negotiable: no quantity reaches that column without a movement.
     *
     * @return true if a movement was written.
     */
    private boolean recordOpeningBalance(
            BatchContext ctx,
            ImportRowState state,
            Product product,
            Map<String, CompanyVendor> createdVendors,
            UUID batchId) {
        Integer quantity = intOf(state, ImportFields.QUANTITY_ON_HAND);
        if (product == null || quantity == null || quantity <= 0) {
            return false;
        }
        CompanyVendor vendor = resolveVendor(ctx, state, createdVendors);
        if (vendor == null) {
            // stockIn refuses a receipt with no supplier once the product has vendor lines, and
            // a continuation row may have added one even though the parent named none.
            vendor = productVendorRepository
                    .findByClientIdAndProductIdAndIsPreferredTrue(ctx.tenantId(), product.getId())
                    .map(ProductVendor::getCompanyVendor)
                    .orElse(null);
        }
        stockManagementService.stockIn(
                product.getId(),
                new StockInRequest(
                        quantity,
                        decimalOf(state, ImportFields.COST_PRICE),
                        OPENING_BALANCE_NOTE,
                        null,
                        vendor == null ? null : vendor.getId(),
                        null,
                        null,
                        OffsetDateTime.now()),
                ctx.actingUserId(),
                batchId);
        return true;
    }

    // ------------------------------------------------------------------- undo

    /**
     * Deactivate what it created, put back what it changed, or say why not (design 6.6).
     *
     * <h2>What blocks it</h2>
     * A created product that has since been transacted on. Note carefully what that does
     * <em>not</em> mean: a product created by an import with an opening balance always has a
     * movement - the one the import itself wrote - so "has any movement" would block every undo
     * of the case this feature exists for. What blocks is a movement from outside this batch, or
     * an opening lot that has been drawn from, because unpicking a real sale to make a button
     * work is precisely what contract section 8.10 forbids.
     *
     * <h2>Deactivate, not delete</h2>
     * {@code products.import_batch_id} is {@code ON DELETE RESTRICT} against the session, orders
     * and movements point at products, and a product id may already be sitting in someone's cart.
     * Deactivation is what "undo" honestly means for a catalog row, and it is what design 6.6
     * asks for.
     */
    @Override
    public UndoOutcome undo(ImportSession session) {
        UUID tenantId = session.getClientId();
        UUID batchId = session.getId();

        List<Product> createdProducts = productRepository.findAllByClientIdAndImportBatchId(tenantId, batchId);
        List<StockMovement> batchMovements =
                stockMovementRepository.findAllByClientIdAndImportBatchIdOrderByOccurredAtAsc(tenantId, batchId);

        List<UndoOutcome.Blocker> blockers = new ArrayList<>();
        for (Product product : createdProducts) {
            if (stockMovementRepository.countMovementsOutsideBatch(product.getId(), tenantId, batchId) > 0) {
                blockers.add(new UndoOutcome.Blocker(0, product.getName(), "Stock has moved since",
                        product.getId().toString()));
            }
        }
        for (StockMovement movement : batchMovements) {
            if (movement.getMovementType() == MovementType.IN
                    && stockMovementAllocationRepository.sumQuantityByInMovementId(movement.getId()) > 0) {
                blockers.add(new UndoOutcome.Blocker(0, movement.getProduct().getName(), "Already sold from",
                        movement.getProduct().getId().toString()));
            }
        }
        if (!blockers.isEmpty()) {
            return UndoOutcome.blocked(
                    // See StockInRowHandler.undo for why this says "all at once" and not
                    // "as a batch" - contract section 8.6 bans the word the spec's own example
                    // sentence happens to use.
                    "%s from this import %s already had stock move since, so it can't be undone all at once. "
                            .formatted(ImportCopy.products(blockers.size()), blockers.size() == 1 ? "has" : "have")
                            + "You can deactivate them one at a time instead.",
                    blockers);
        }

        int movementsReversed = reverseMovements(tenantId, batchId, batchMovements);
        for (Product product : createdProducts) {
            product.setActive(false);
        }
        int reverted = revertUpdatedProducts(session, tenantId);

        List<CommitPreview.Line> lines = new ArrayList<>();
        if (!createdProducts.isEmpty()) {
            lines.add(new CommitPreview.Line("create", "Deactivated", createdProducts.size(),
                    ImportCopy.products(createdProducts.size()) + " this import created were switched off"));
        }
        if (reverted > 0) {
            lines.add(new CommitPreview.Line("update", "Restored", reverted,
                    ImportCopy.products(reverted) + " were put back the way they were"));
        }
        if (movementsReversed > 0) {
            lines.add(new CommitPreview.Line("stock", "Stock", movementsReversed,
                    "Opening stock was reversed for " + ImportCopy.products(movementsReversed)));
        }
        return UndoOutcome.done("This import has been undone.", lines, createdProducts.size(), reverted, movementsReversed);
    }

    /**
     * Compensating {@code ADJUSTMENT} rows, never deletions (contract section 8.10). The ledger
     * is the record of what we believed and when; a reversal is another fact about the world,
     * not an erasure of the first one.
     */
    private int reverseMovements(UUID tenantId, UUID batchId, List<StockMovement> batchMovements) {
        int reversed = 0;
        for (StockMovement movement : batchMovements) {
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
                    .note("Reversing an import that was undone")
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
        return reversed;
    }

    private int revertUpdatedProducts(ImportSession session, UUID tenantId) {
        int reverted = 0;
        for (com.procurepal_services.stock_bridge_api.entity.ImportSessionRow row : rowsOf(session)) {
            Map<String, Object> normalized = row.getNormalized();
            if (normalized == null || !(normalized.get(ImportFields.BEFORE) instanceof Map<?, ?> snapshot)) {
                continue;
            }
            if (row.getResolvedEntityId() == null) {
                continue;
            }
            Product product = productRepository.findByIdAndClientId(row.getResolvedEntityId(), tenantId).orElse(null);
            if (product == null) {
                continue;
            }
            product.setName(asString(snapshot.get(ImportFields.NAME)));
            product.setDescription(asString(snapshot.get(ImportFields.DESCRIPTION)));
            product.setUnitPrice(asDecimal(snapshot.get(ImportFields.UNIT_PRICE)));
            product.setLowStockThreshold(asInteger(snapshot.get(ImportFields.LOW_STOCK_THRESHOLD)));
            product.setUnitOfMeasure(asString(snapshot.get(ImportFields.UNIT_OF_MEASURE)));
            product.setPackagingUnit(asString(snapshot.get(ImportFields.PACKAGING_UNIT)));
            product.setPackagingSize(asDecimal(snapshot.get(ImportFields.PACKAGING_SIZE)));
            reverted++;
        }
        return reverted;
    }

    /**
     * Rows are reached by session id, and that is safe for exactly the reason M1 gave.
     *
     * <p>{@code ImportSessionRow} has no by-id-alone finder on purpose: rows carry no
     * {@code client_id}, and the only thing enforcing tenancy on them is having loaded the
     * session through the tenant-scoped session repository first. The engine has already done
     * that - {@code undo} is only ever reached through a session the caller was proven to own -
     * so a lookup by that session's id inherits its tenancy rather than bypassing it.
     */
    private List<com.procurepal_services.stock_bridge_api.entity.ImportSessionRow> rowsOf(ImportSession session) {
        return importSessionRowRepository.findAllBySessionIdOrderByExcelRowAsc(session.getId());
    }

    // ----------------------------------------------------------------- helpers

    private Product findBySku(UUID tenantId, ImportBatchCache cache, String sku) {
        return cache.get(CACHE_PRODUCTS_BY_SKU, sku.toUpperCase(Locale.ROOT),
                key -> productRepository.findByClientIdAndSku(tenantId, sku).orElse(null));
    }

    private boolean hasMovements(UUID tenantId, ImportBatchCache cache, UUID productId) {
        return Boolean.TRUE.equals(cache.get(CACHE_MOVEMENT_EXISTS, productId,
                id -> stockMovementRepository.existsByProductIdAndClientId(id, tenantId)));
    }

    private boolean isSeller(UUID tenantId) {
        if (tenantId == null) {
            return false;
        }
        return sellerDirectory.findSellerOfRecord(tenantId).map(Client::canSell).orElse(false);
    }

    /** Whether the caller holds an authority, for the permission-gated inline-creation offers. */
    static boolean hasAuthority(String authority) {
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication();
        return authentication != null
                && authentication.getAuthorities().stream()
                        .anyMatch(granted -> authority.equals(granted.getAuthority()));
    }

    private static BigDecimal decimalOf(ImportRowState state, String field) {
        return asDecimal(state.value(field));
    }

    private static Integer intOf(ImportRowState state, String field) {
        return asInteger(state.value(field));
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static BigDecimal asDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        return new BigDecimal(value.toString());
    }

    private static Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.valueOf(value.toString());
    }
}
