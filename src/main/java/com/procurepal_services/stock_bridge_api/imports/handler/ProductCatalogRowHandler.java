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
import com.procurepal_services.stock_bridge_api.product.bulk.ProductExcelService;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasure;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOption;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasureRole;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOptions;
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
 * Design 6.7 asks for those words exactly. A created row's {@code opening_stock} writes a
 * real {@code IN} movement through {@code StockManagementService.stockIn} - a genuine lot with a
 * vendor and a cost, converging on the same ledger bulk stock-in uses - and an updated row's
 * does nothing at all. So there is exactly one way to add stock to a product that already
 * exists, and nobody ever has to work out which of two tools moves a number.
 *
 * <h2>Both quantity columns count PACKS - UNIT_UX_CONTRACT.md section 9.1</h2>
 * {@code opening_stock} and {@code low_stock_alert_at} are counted in the row's own pack
 * whenever it declares one ({@code pack} + {@code units_per_pack}), and in its stock unit when
 * it does not. Thirty beside a Keg of 50 ml is 1,500 ml. Decimals are accepted, because thirty
 * kegs and a half-full one is a real shelf; a conversion that rounds to zero is refused rather
 * than stored as nothing (section 3.1).
 *
 * <p>{@code cost_price} deliberately does NOT follow - it stays per stock unit, per ml and not
 * per keg (section 9.2). That is why the opening balance below hands {@code stockIn} a quantity
 * already converted and a null {@code unit}: one request cannot carry two bases, and the price
 * is the one that must not be divided.
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
     *
     * <h2>The order is the sheet's order, and the sheet's order is now load bearing</h2>
     * UNIT_UX_CONTRACT.md section 9 regrouped the columns: identity, then how you count it
     * ({@code stock_unit}, {@code pack}, {@code units_per_pack}), then how much
     * ({@code opening_stock}, {@code low_stock_alert_at}, the two prices), then supplier. The
     * counting columns come first because since section 9.1 they DECIDE what the quantity
     * columns mean, and a review grid that showed them in a different order from the spreadsheet
     * the user just uploaded would be describing a different file.
     *
     * <h2>opening_stock_counted_in is gone</h2>
     * It asked which unit the number beside it was in. The row answers that by itself now - a
     * declared pack means packs - so the column was a question whose answer was already two
     * cells to its left, and section 9.1 deletes it outright: no alias, no legacy reading, no
     * fallback. Nothing has reached production, so there is no saved sheet whose bare number
     * needs its old meaning preserved, and that was its only remaining argument.
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
        fields.add(ImportFieldDescriptor.enumeration(ImportFields.STOCK_UNIT, ImportCopy.Labels.STOCK_UNIT,
                false, "What you count this product in - kilograms, millilitres, pieces. Everything we store "
                        + "for it is counted this way.",
                RowValues.options(UnitOfMeasure.baseUnits())));
        fields.add(ImportFieldDescriptor.enumeration(ImportFields.PACK, ImportCopy.Labels.PACK, false,
                "The container you buy and sell it by - Bag, Keg, Carton. Leave it blank if you sell it loose.",
                RowValues.options(UnitOfMeasure.packagingUnits())));
        fields.add(ImportFieldDescriptor.of(ImportFields.UNITS_PER_PACK, ImportCopy.Labels.UNITS_PER_PACK,
                ImportFieldDescriptor.Type.NUMBER, false,
                "How much is in one pack. Stock unit Milliliter + Pack Keg + 50 means a 50 ml keg."));
        // NUMBER rather than INTEGER, on both quantity columns. Section 9.1 accepts decimals
        // because they now count PACKS, and thirty kegs and a half-full one is a real shelf that
        // an integer cannot say. The grid renders the number the user typed; the "= 1,500 ml"
        // underneath it is _base_quantity_text.
        fields.add(ImportFieldDescriptor.of(ImportFields.OPENING_STOCK, ImportCopy.Labels.OPENING_STOCK,
                ImportFieldDescriptor.Type.NUMBER, false,
                "How much you have right now, counted in packs when this row has one - 30 beside a Keg of "
                        + "50 ml means 30 kegs, not 30 ml. Only used on products we are creating, and it is "
                        + "recorded as an opening stock entry."));
        fields.add(ImportFieldDescriptor.of(ImportFields.LOW_STOCK_ALERT_AT, ImportCopy.Labels.LOW_STOCK_ALERT_AT,
                ImportFieldDescriptor.Type.NUMBER, false,
                "We warn you when stock falls to this much, counted the same way as opening stock - in "
                        + "packs if this row has one."));
        fields.add(ImportFieldDescriptor.of(ImportFields.COST_PRICE, ImportCopy.Labels.COST_PER_STOCK_UNIT,
                ImportFieldDescriptor.Type.MONEY, false,
                "What you pay for ONE of whatever the opening stock counts - one keg if this row has a "
                        + "pack, one ml if it does not. On a new product with an opening stock, this becomes "
                        + "that stock's cost."));
        // Read-only and never read for a value - UNIT_UX_CONTRACT.md section 9.5. A live Excel
        // formula that restates the five columns above it as one sentence, so it is declared here
        // only so ImportColumnMapper recognises the header and the grid can show it behind "Show
        // every column"; ProductCatalogRowHandler.validate() never looks it up.
        fields.add(new ImportFieldDescriptor(ImportFields.WHAT_YOU_ARE_ADDING, "What you're adding",
                ImportFieldDescriptor.Type.TEXT, false, true, false,
                "For your reference: a live summary of this row, built from the columns to its left. "
                        + "It updates itself in Excel and is not read when you upload.", null, null));
        if (seller) {
            fields.add(ImportFieldDescriptor.of(ImportFields.UNIT_PRICE, "Selling price",
                    ImportFieldDescriptor.Type.MONEY, true,
                    "Your marketplace selling price, for one stock unit - per ml, not per keg."));
        }
        fields.add(new ImportFieldDescriptor(ImportFields.VENDOR_NAME, ImportCopy.Labels.SUPPLIER,
                ImportFieldDescriptor.Type.REFERENCE, false, false, false,
                "Who you buy this from. Repeat the product code on the next row to add a second supplier.", null, null));
        fields.add(ImportFieldDescriptor.text(ImportFields.VENDOR_SKU, ImportCopy.Labels.SUPPLIERS_CODE,
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

        // These four label strings are user-facing: RowValues.unitCode splices them into "%s is
        // %s, which belongs in the %s column rather than here". They are section 1's locked names
        // rather than literals, so the sentence names each column the same way the grid header
        // and the spreadsheet comment do - "Pack" and "Stock unit", never "Packaging" or "unit of
        // measure" (both banned spellings for these two concepts).
        //
        // Read BEFORE the two quantity columns, and that order is section 9.1 in code: what
        // opening_stock and low_stock_alert_at COUNT depends on whether this row declared a pack.
        String unitOfMeasure = RowValues.unitCode(ctx, out, ImportFields.STOCK_UNIT,
                UnitOfMeasureRole.BASE, ImportCopy.Labels.STOCK_UNIT, ImportCopy.Labels.PACK, subject);
        String packagingUnit = RowValues.unitCode(ctx, out, ImportFields.PACK,
                UnitOfMeasureRole.PACKAGING, ImportCopy.Labels.PACK, ImportCopy.Labels.STOCK_UNIT, subject);
        BigDecimal packagingSize = RowValues.decimal(
                ctx, out, ImportFields.UNITS_PER_PACK, ImportCopy.Labels.UNITS_PER_PACK, subject);

        validatePackagingCoherence(ctx, out, subject, unitOfMeasure, packagingUnit, packagingSize);

        UnitOption countedIn = countedIn(unitOfMeasure, packagingUnit, packagingSize);
        BigDecimal quantity = quantityColumn(ctx, out, ImportFields.OPENING_STOCK,
                ImportCopy.Labels.OPENING_STOCK, subject, countedIn, unitOfMeasure, true);
        quantityColumn(ctx, out, ImportFields.LOW_STOCK_ALERT_AT,
                ImportCopy.Labels.LOW_STOCK_ALERT_AT, subject, countedIn, unitOfMeasure, false);

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
        boolean unitProvided = ctx.has(ImportFields.STOCK_UNIT);
        boolean packagingUnitProvided = ctx.has(ImportFields.PACK);
        boolean packagingSizeProvided = ctx.has(ImportFields.UNITS_PER_PACK);

        if (packagingUnitProvided != packagingSizeProvided) {
            if (packagingUnitProvided) {
                out.error(ImportFields.UNITS_PER_PACK, "PACKAGING_SIZE_REQUIRED",
                        "How many stock units are in one %s of %s? A pack needs a size to be useful."
                                .formatted(ImportCopy.unitLabel(packagingUnit == null ? "pack" : packagingUnit), subject));
            } else {
                out.error(ImportFields.PACK, "PACKAGING_UNIT_REQUIRED",
                        "%s says there are %s units in a pack, but not what kind of pack. Bag, carton, drum?"
                                .formatted(subject, ImportCopy.count(packagingSize)));
            }
        }
        if (!unitProvided && (packagingUnitProvided || packagingSizeProvided)) {
            out.error(ImportFields.STOCK_UNIT, "UNIT_REQUIRED_FOR_PACKAGING",
                    // "Stock unit", not "measured in" - UNIT_UX_CONTRACT.md section 1 locks the
                    // name and bans that spelling. The error also names the column's own label,
                    // so the sentence points at the cell the user has to fill rather than
                    // describing it in different words than the grid header does.
                    "Before we can record how %s is packed, we need to know its stock unit - what every "
                            .formatted(subject)
                            + "quantity of it is counted in.");
        }
        // Reachable only since COUNT units began serving either role: "a Piece of 34 Pieces".
        // The BASE/PACKAGING split used to make this impossible by construction, so relaxing it
        // means stating the invariant instead of inheriting it. A pack of itself converts nothing.
        if (packagingUnit != null && packagingUnit.equalsIgnoreCase(unitOfMeasure)) {
            out.error(ImportFields.PACK, "PACKAGING_UNIT_SAME_AS_STOCK_UNIT",
                    "%s is counted in %s, so it cannot also be packed in %s - name the container they come in, "
                            .formatted(subject, ImportCopy.unitLabel(unitOfMeasure), ImportCopy.unitLabel(packagingUnit))
                            + "or leave the pack columns empty.");
        }
    }

    /**
     * One of the two quantity columns, read under UNIT_UX_CONTRACT.md section 9.1: <b>packs when
     * this row declares one, stock units when it does not</b>.
     *
     * <h2>What this replaced, twice</h2>
     * A cross-field <em>warning</em> used to live here - "12 kg, did you mean 12 bags (480 kg)?",
     * fired when {@code opening_stock <= packaging_size}. It was a guess standing in for a fact,
     * and its predicate was unsound in both directions: it interrogated somebody who genuinely
     * had 12 kg and said nothing at all to somebody who typed 60 meaning 60 bags, because 60 is
     * greater than 40. Loud on the cheap mistake, silent on the expensive one.
     *
     * <p>That warning was replaced by an {@code opening_stock_counted_in} column, on the
     * reasoning that a quantity whose unit is inferred from a neighbour is exactly what section 1
     * exists to forbid. Sound reasoning, wrong instrument. A user then said, unprompted, what the
     * number had meant to them all along - "opening_stock is the amount of the packaging unit the
     * company wants to add... 30 bags of 80 kg rice = 2,400 kg" - and they were right, and they
     * had independently arrived at NetSuite's purchase-unit / sale-unit split. The unit is not
     * being inferred from a neighbour; the neighbour IS the declaration, the same way "30 bags"
     * declares its unit in English. So the column asked a question the row had already answered,
     * and section 9.1 deletes it.
     *
     * <h2>Decimals, and the two refusals</h2>
     * The entered number may be fractional - {@code RowValues.wholeNumber} is the wrong parser
     * for a count of packs, because thirty kegs and a half-full one is a real shelf. Conversion
     * rounds HALF_UP at scale 0, section 3.1's rounding, since every stored quantity column is an
     * integer. Two results are refused rather than stored: an overflow (two billion bags of
     * fifty is a mistyped cell, and {@code stock_movements.quantity} is an int, so a commit would
     * otherwise fail mid-file), and a conversion that rounds to <b>zero</b>, which section 3.1
     * requires be a refusal and never a silent nothing.
     *
     * <h2>What is stored, and what is shown under it</h2>
     * The normalized value is the number the user TYPED, in packs - not the converted figure.
     * The review grid renders that cell and lets them edit it, and replacing their 30 with 1,500
     * would be answering a question they did not ask. Non-negotiable 3 is met the way the
     * stock-in grid meets it: {@code _base_quantity_text} carries the "= 1,500 ml" that renders
     * beneath. Every consumer downstream converts through {@link #stockUnitsOf}, which applies
     * this same factor once.
     *
     * @param primary whether this is the column whose conversion the grid echoes. Only
     *     {@code opening_stock} gets the echo; a second "= 250 ml" under the alert threshold
     *     would be two conversions competing for one line of space.
     * @return the number as typed, in {@code countedIn}'s terms, or null when the cell was blank
     *     or unusable.
     */
    private BigDecimal quantityColumn(
            RowContext ctx,
            RowValidation.Builder out,
            String field,
            String label,
            String subject,
            UnitOption countedIn,
            String stockUnitCode,
            boolean primary) {
        BigDecimal entered = RowValues.decimal(ctx, out, field, label, subject);
        if (entered == null) {
            return null;
        }
        Integer stockUnits = toStockUnits(countedIn, entered);
        if (stockUnits == null) {
            out.error(field, "NUMBER_TOO_LARGE",
                    "%s is larger than we can record for %s.".formatted(label, subject));
            out.value(field, null);
            return null;
        }
        if (stockUnits == 0 && entered.signum() > 0) {
            out.error(field, "ROUNDS_TO_ZERO",
                    "%s of %s is less than one whole %s, and we can only record whole ones - enter a larger "
                            .formatted(ImportCopy.count(entered), spokenOf(countedIn), symbolOrUnits(stockUnitCode))
                            + "amount, or count " + subject + " in a smaller unit.");
            out.value(field, null);
            return null;
        }
        if (primary && !countedIn.isStockUnit() && stockUnits > 0) {
            out.value(ImportFields.BASE_QUANTITY_TEXT, ImportCopy.baseQuantityText(stockUnits, stockUnitCode));
        }
        return entered;
    }

    /**
     * Which unit a row's quantities are counted in - section 9.1's rule expressed as section
     * 2.1's set rather than as a fresh {@code if}.
     *
     * <p>"The pack when the row declares one, the stock unit when it does not" is exactly what
     * {@code UnitOptions.defaultOption} already means: section 2.1 defines {@code isDefault} as
     * the product's own pack if it has one, else the stock unit. Asking the set rather than
     * re-deriving the rule is what keeps this sheet, the product form and the stock modals from
     * ever disagreeing about what a number means - the single failure the whole remediation
     * exists to undo.
     *
     * <p>Scoped to the ROW, deliberately, and not widened to the stored product on an update.
     * Section 9.1 says "whenever the row declares one", and the row is what the person was
     * looking at when they typed. Our own export always writes the pack columns, so the
     * export-edit-reupload path always declares; a partial supplier price-list that mentions
     * neither pack column is saying "stock units", which is the same thing it has always said.
     */
    private UnitOption countedIn(String stockUnitCode, String packagingUnit, BigDecimal unitsPerPack) {
        List<UnitOption> options = UnitOptions.forProduct(stockUnitCode, packagingUnit, unitsPerPack);
        return UnitOptions.defaultOption(options)
                .or(() -> UnitOptions.stockUnitOption(options))
                .orElseGet(() -> new UnitOption(UnitOptions.NO_STOCK_UNIT_CODE, UnitOptions.NO_STOCK_UNIT_LABEL,
                        BigDecimal.ONE, true, true, false));
    }

    /** {@link #countedIn} for a row that has already been validated. */
    private UnitOption countedIn(ImportRowState state) {
        return countedIn(
                state.text(ImportFields.STOCK_UNIT),
                state.text(ImportFields.PACK),
                decimalOf(state, ImportFields.UNITS_PER_PACK));
    }

    /**
     * A validated row's quantity column in STOCK units - the one conversion every commit-side
     * consumer goes through, so that the ledger, the product's alert threshold and the preview
     * total cannot disagree about what "30" meant.
     */
    /**
     * A money column read as "per whatever this row is counted in", converted to the per-stock-unit
     * figure everything downstream stores - contract section 3.2's division, the price twin of
     * {@link #stockUnitsOf}.
     *
     * <h2>Why this exists, and what it replaces</h2>
     * Section 9.2 originally anchored {@code cost_price} to the STOCK UNIT on entry as well as in
     * storage, and recorded that as a deliberate divergence from Odoo (whose vendor pricelist price
     * is per Purchase UoM) and NetSuite (whose Purchase Price is per purchase unit), both of which
     * let you type the per-pack figure and convert for you. The argument was comparability across
     * suppliers with different packs.
     *
     * <p>It did not survive contact with a real sheet. A row read <b>45 baskets</b> of mango beside
     * <b>78,000</b>, and the screen dutifully echoed "= N2,340,000.00 / basket" - arithmetically
     * perfect, since 78,000 x 30 is exactly that, and completely wrong, because 78,000 was the price
     * of ONE basket. The row was stating its quantity in packs and its price per stock unit: the
     * mixed basis this whole remediation exists to remove, sitting in two adjacent cells.
     *
     * <p>So entry follows the pack, like every other number on the row, and storage stays per stock
     * unit, which is what actually delivers the comparability the old rule was reaching for. This is
     * also what the STOCK-IN sheet has always done - its {@code cost_per_unit} is "per whatever this
     * row's counted_in says" - so the two sheets now agree instead of contradicting each other.
     */
    private BigDecimal perStockUnitPriceOf(ImportRowState state, String field) {
        BigDecimal entered = decimalOf(state, field);
        if (entered == null) {
            return null;
        }
        return countedIn(state).toStockUnitPrice(entered);
    }

    private Integer stockUnitsOf(ImportRowState state, String field) {
        BigDecimal entered = decimalOf(state, field);
        return entered == null ? null : toStockUnits(countedIn(state), entered);
    }

    /**
     * {@code round(entered x factorToStockUnit)}, HALF_UP scale 0 - contract section 3.1 - with
     * the overflow turned into an answer rather than an exception.
     *
     * <h2>Why the arithmetic is here at all</h2>
     * It should be {@code UnitOption.toStockUnitQuantity}, and for an integer entry it is exactly
     * that method's result, digit for digit. That method takes an {@code int}, and section 9.1
     * accepts decimals, so a count of 30.5 kegs has nowhere to go through it. The factor is still
     * M1's - derived once by {@code UnitOptions} from the row's own declaration and never
     * recomputed here - so what is duplicated is one multiply and one rounding mode, not the
     * decision this remediation exists to keep single.
     *
     * <p>The right fix is a {@code toStockUnitQuantity(BigDecimal)} overload on {@code UnitOption}
     * that this method and {@code StockInRowHandler.toStockUnits} both delegate to.
     * {@code product/unit/**} is not this module's to write, so it is named here instead of done.
     *
     * @return the amount in stock units, or null when it does not fit an int.
     */
    private static Integer toStockUnits(UnitOption option, BigDecimal entered) {
        BigDecimal converted = option.factorToStockUnit().multiply(entered);
        if (converted.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            return null;
        }
        return converted.setScale(0, java.math.RoundingMode.HALF_UP).intValue();
    }

    /** "kegs", "kg" - one option as it reads inside a sentence. */
    private static String spokenOf(UnitOption option) {
        String phrase = UnitOptions.spokenPhrase(option);
        int of = phrase.indexOf(" of ");
        return of > 0 ? phrase.substring(0, of) : phrase;
    }

    /** The stock unit's short symbol, or section 2.1's "units" for a product that has none. */
    private static String symbolOrUnits(String stockUnitCode) {
        String symbol = UnitOptions.symbolOf(stockUnitCode);
        return symbol.isEmpty() ? UnitOptions.NO_STOCK_UNIT_LABEL : symbol;
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
            BigDecimal quantity,
            String unitOfMeasure,
            BigDecimal costPrice) {

        // (1) Quantity is ignored, and the grid says so. Contract section 8.8 - never silently.
        // The message is contract section 4's, verbatim, because the frontend renders it as-is
        // and the mock the review screen was built against contains this exact sentence.
        if (quantity != null && quantity.signum() > 0) {
            out.warning(ImportFields.OPENING_STOCK, "QUANTITY_IGNORED_ON_UPDATE",
                    "Quantity is ignored when updating an existing product — use Record stock you received.");
        }

        // (2) unit_of_measure is immutable once the product has any movement. Contract 8.12.
        if (unitOfMeasure != null
                && !Objects.equals(unitOfMeasure, existing.getUnitOfMeasure())
                && hasMovements(ctx.tenantId(), ctx.cache(), existing.getId())) {
            String current = ImportCopy.unitLabel(existing.getUnitOfMeasure());
            out.error(ImportFields.STOCK_UNIT, "UNIT_IMMUTABLE",
                    "%s has already had stock recorded in %s, so we cannot change its stock unit - "
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
                ImportFields.OPENING_STOCK, ImportFields.LOW_STOCK_ALERT_AT,
                ImportFields.STOCK_UNIT, ImportFields.PACK, ImportFields.UNITS_PER_PACK);
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
    /**
     * Only {@code vendor_name}. It is the one column this handler asks a question about, and
     * {@link #resolveVendor} handles all five arms of the answer - so the engine's generic
     * LITERAL/BLANK substitution must stay off it. Every other column is an ordinary value and
     * takes the generic path, which is what makes the unit column's bulk fix work at all.
     */
    @Override
    public java.util.Set<String> selfResolvedColumns() {
        return java.util.Set.of(ImportFields.VENDOR_NAME);
    }

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

    /**
     * The opening-balance total, in ledger terms - UNIT_UX_CONTRACT.md section 6.3 applied to the
     * catalog import.
     *
     * <h2>What this used to say, and why it was the same defect as P0-4</h2>
     * It summed every row's opening stock into one number and then labelled it with the single
     * unit if the file happened to use one, else the bare word "units". A catalog of rice in
     * kilograms and cartons of oil previewed as "3,400 units" - a quantity that is not recorded
     * anywhere, cannot be checked against anything, and reads as though we had understood the
     * file. Section 6.3 forbids exactly that: "a bare sum of mixed entered quantities must not
     * appear anywhere", and the catalog import is named in the following sentence.
     *
     * <p>The totals are in stock units even though the cells are in packs, and that is section
     * 6.3's rule rather than a convenience: "a bare sum of mixed entered quantities must not
     * appear anywhere", and since section 9.1 a column of bare numbers on a catalog file IS
     * mixed - thirty kegs on one row, six hundred pieces on the next. Each stock unit gets its
     * own total, converted through the same factor the commit will use.
     *
     * <p>There is no bracketed "(190 kegs)" half at the summary level, and its absence is
     * deliberate: it would have to name a different pack for every row it summed. The per-row
     * pairing non-negotiable 3 asks for lives on the row, as {@code _base_quantity_text}.
     */
    private String quantityPhrase(Tally tally) {
        return ImportCopy.quantityTotals(tally.openingByStockUnit);
    }

    private static final class Tally {
        int creates;
        int updates;
        int skipped;
        int vendorsToCreate;
        int openingProducts;
        /** Stock-unit symbol to the opening stock counted in it. Insertion-ordered by file order. */
        final Map<String, Long> openingByStockUnit = new LinkedHashMap<>();
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
                // Summed in LEDGER terms - section 6.3 - which since section 9.1 means
                // converting the row's pack count first. A preview that added up bags and
                // millilitres because both cells happen to hold a bare number would be the exact
                // "bare sum of mixed entered quantities" 6.3 forbids.
                Integer quantity = stockUnitsOf(state, ImportFields.OPENING_STOCK);
                if (quantity != null && quantity > 0) {
                    tally.openingProducts++;
                    tally.openingByStockUnit.merge(
                            symbolOrUnits(state.text(ImportFields.STOCK_UNIT)), (long) quantity, Long::sum);
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
                .costPrice(perStockUnitPriceOf(state, ImportFields.COST_PRICE))
                // Zero, always. Contract section 8.1: no quantity reaches quantity_on_hand
                // without a StockMovement, and the opening-balance stockIn below is the only
                // thing allowed to move it.
                .quantityOnHand(0)
                .incomingQuantity(0)
                // Section 9.1: the cell counts packs when the row declares one, and
                // products.low_stock_threshold is compared against quantity_on_hand, which is in
                // stock units. Converting here rather than storing what was typed is what stops
                // a 5-keg alert firing at 5 ml.
                .lowStockThreshold(stockUnitsOf(state, ImportFields.LOW_STOCK_ALERT_AT))
                .unitOfMeasure(state.text(ImportFields.STOCK_UNIT))
                .packagingUnit(state.text(ImportFields.PACK))
                .packagingSize(decimalOf(state, ImportFields.UNITS_PER_PACK))
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
        before.put(ImportFields.LOW_STOCK_ALERT_AT, product.getLowStockThreshold());
        before.put(ImportFields.STOCK_UNIT, product.getUnitOfMeasure());
        before.put(ImportFields.PACK, product.getPackagingUnit());
        before.put(ImportFields.UNITS_PER_PACK,
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
        Integer alertAt = stockUnitsOf(state, ImportFields.LOW_STOCK_ALERT_AT);
        if (alertAt != null) {
            product.setLowStockThreshold(alertAt);
        }
        if (state.text(ImportFields.STOCK_UNIT) != null) {
            product.setUnitOfMeasure(state.text(ImportFields.STOCK_UNIT));
        }
        if (state.text(ImportFields.PACK) != null) {
            product.setPackagingUnit(state.text(ImportFields.PACK));
        }
        if (decimalOf(state, ImportFields.UNITS_PER_PACK) != null) {
            product.setPackagingSize(decimalOf(state, ImportFields.UNITS_PER_PACK));
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
        BigDecimal costPrice = perStockUnitPriceOf(state, ImportFields.COST_PRICE);
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
        // In STOCK units, converted once, here. Section 9.1's cell counts packs.
        Integer quantity = stockUnitsOf(state, ImportFields.OPENING_STOCK);
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
                        // The quantity above is ALREADY in stock units and the unit below is
                        // therefore null - factor 1, stockIn converts nothing. That is not a
                        // shortcut, it is the only shape that can carry this row.
                        //
                        // A StockInRequest has ONE unit for both of its numbers: the quantity is
                        // per that unit and the price is per that unit (section 3.2). Section 9.1
                        // puts the catalog row's quantity in PACKS while section 9.2 keeps its
                        // cost_price per STOCK UNIT - deliberately, because a per-stock-unit cost
                        // is the only figure comparable across suppliers whose packs differ. A
                        // mixed-basis pair like that cannot travel through one `unit` field:
                        // passing "KEG" would make stockIn divide a price that is already per ml
                        // by fifty, which is P0-1 in the opposite direction.
                        //
                        // (It would also refuse 30.5 kegs outright - StockInRequest.quantity is
                        // an Integer, and section 9.1 accepts decimals. Widening that field to a
                        // BigDecimal is M1's call and would let this call site hand over what the
                        // user typed, which is what StockMovement's entered_quantity/entered_unit
                        // display columns want; until then those two stay null for a catalog
                        // opening balance, and the review grid carries the echo instead.)
                        perStockUnitPriceOf(state, ImportFields.COST_PRICE),
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
            product.setLowStockThreshold(asInteger(snapshot.get(ImportFields.LOW_STOCK_ALERT_AT)));
            product.setUnitOfMeasure(asString(snapshot.get(ImportFields.STOCK_UNIT)));
            product.setPackagingUnit(asString(snapshot.get(ImportFields.PACK)));
            product.setPackagingSize(asDecimal(snapshot.get(ImportFields.UNITS_PER_PACK)));
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
