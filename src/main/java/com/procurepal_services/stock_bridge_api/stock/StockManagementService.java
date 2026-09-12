package com.procurepal_services.stock_bridge_api.stock;

import com.procurepal_services.stock_bridge_api.companyvendor.ProductVendorService;
import com.procurepal_services.stock_bridge_api.entity.CompanyVendor;
import com.procurepal_services.stock_bridge_api.entity.MovementType;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.entity.StockMovement;
import com.procurepal_services.stock_bridge_api.entity.StockMovementAllocation;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.product.ProductNotFoundException;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasure;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOption;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOptions;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductVendorRepository;
import com.procurepal_services.stock_bridge_api.repository.StockMovementAllocationRepository;
import com.procurepal_services.stock_bridge_api.repository.StockMovementRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import com.procurepal_services.stock_bridge_api.stock.dto.AllocationResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.ProductLotResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockAdjustmentRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.StockInRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMovementResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMovementSummaryResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMutationResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockOutRequest;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every mutating method here locks the product row (ProductRepository's
 * PESSIMISTIC_WRITE query) before reading quantity_on_hand, so a concurrent
 * stock-in/out/adjustment against the same product blocks instead of racing
 * on a read-modify-write - the second transaction sees the first one's
 * committed quantity once its lock is granted, never a stale value. This was
 * chosen over an atomic `UPDATE ... WHERE quantity_on_hand >= ?` because the
 * product row needs to be loaded as a managed entity anyway (to return it in
 * the response, and to update it via the same Hibernate Session that writes
 * the ledger row in one transaction) - a lock on that same read is simpler
 * than a separate conditional-update statement plus a follow-up read, for
 * inventory volumes where lock contention isn't a real throughput concern.
 *
 * <h2>V19: costing and stock-out allocation</h2>
 * {@code stockIn} now recalculates {@code Product.costPrice} as a weighted average on every
 * call (MULTI_VENDOR_INVENTORY_DESIGN.md section 5.3/6) and, once a {@code companyVendorId} is
 * supplied, finds-or-creates the corresponding {@code ProductVendor} line via {@link
 * ProductVendorService}. {@code stockOut} now allocates against specific {@code IN} movements
 * (lots) via {@link StockMovementAllocation} rather than only checking the cached counter - true
 * per-shipment FIFO by default, or an explicit caller-supplied allocation list for the
 * "advanced" manual override (design doc section 6). {@code Product.quantityOnHand} stays the
 * fast cached counter it always was; the lot ledger is the source of truth underneath it, not a
 * replacement for it.
 *
 * <h2>V21: quantity and price are converted by the SAME factor, or neither is</h2>
 * Read {@link #resolveEntry} before changing anything in {@code stockIn}/{@code stockOut}. Until
 * V21 this class converted the quantity half of an entry into base units and passed the price
 * half through untouched, so "20 bags at &#8358;45,000 per bag" on a 50 kg-bag product recorded
 * 1,000 kg at &#8358;45,000 <em>per kg</em> - a fifty-fold error, written silently by
 * {@link #recomputeWeightedAverageCost} and by {@code ProductVendor.lastCostPrice}, and then
 * compounded into every later weighted average (UNIT_UX_REMEDIATION_PLAN.md section 3, P0-1).
 *
 * <p>There is now exactly one place a request's {@code unit} is interpreted -
 * {@link #resolveEntry} - it resolves against the product's derived unit set rather than a
 * hand-rolled comparison, and it returns the quantity and the price already converted by the one
 * factor it found. Everything downstream of it consumes only per-stock-unit figures:
 * {@code Product.costPrice}, {@code ProductVendor.lastCostPrice},
 * {@code StockMovement.unitPriceAtTime} and the cheaper-vendor hint (UNIT_UX_CONTRACT.md section
 * 3.2). What the user actually typed is preserved on the movement's {@code entered*} columns as
 * a display fact that nothing computes from (section 3.3).
 *
 * <p>A request that omits {@code unit} resolves to the stock unit, whose factor is 1, and both
 * conversions become identities - which is why closing a fifty-fold hole changed no existing
 * caller (contract non-negotiable 8).
 */
@Service
@RequiredArgsConstructor
public class StockManagementService {

    private final ProductRepository productRepository;
    private final StockMovementRepository stockMovementRepository;
    private final StockMovementAllocationRepository stockMovementAllocationRepository;
    private final ProductVendorRepository productVendorRepository;
    private final UserRepository userRepository;
    /**
     * Owns the {@code ProductVendor} find-or-create/receipt bookkeeping and the cheaper-vendor
     * comparison - see that class for why this logic lives there rather than being duplicated
     * here.
     */
    private final ProductVendorService productVendorService;

    /**
     * Records a delivery. Weighted-average cost is recalculated unconditionally (guarding the
     * divide-by-zero/no-prior-cost case by simply taking the new price - see {@link
     * #recomputeWeightedAverageCost}); the {@code ProductVendor} line is found-or-created only
     * when {@code request.companyVendorId()} is supplied, which is REQUIRED once the product
     * already has at least one vendor line on file (see {@link CompanyVendorRequiredException}).
     * A product with zero vendor lines may still receive stock with none - the very first
     * stock-in that DOES supply one is what creates that first line, automatically preferred.
     *
     * <h2>V20: occurredAt, and why it is validated here rather than by an annotation</h2>
     * {@code request.occurredAt()} is when the delivery actually happened; null means now, which
     * is what every pre-V20 caller said implicitly. It is the one thing that makes bulk stock-in
     * of last month's purchases sort correctly in FIFO - see {@code StockMovement.occurredAt} for
     * the full reasoning and {@link #resolveOccurredAt} for the not-in-the-future rule and the
     * day of clock-skew grace it deliberately allows.
     */
    @Transactional
    public StockMutationResponse stockIn(UUID productId, StockInRequest request, UUID actingUserId) {
        return stockIn(productId, request, actingUserId, null);
    }

    /**
     * Converts a quantity expressed in {@code unit} into this product's stock-unit quantity,
     * via the exact same {@link #resolveEntry} resolution {@link #stockIn}/{@code stockOut} use
     * for the ledger itself - null/blank {@code unit} for "already the stock unit", an unknown
     * or unconvertible one for {@link InvalidStockUnitException}, never a silent guess.
     *
     * <p>For a caller that has to move a bare quantity between two DIFFERENT products'
     * {@code incomingQuantity} counters before a {@link #stockIn} call ever runs - see
     * {@code IncomingStockService.receive}'s marketplace-receipt relink, where the order line's
     * unit is the SELLER's catalog product's, not necessarily the buyer-chosen product's it is
     * now being counted against.
     *
     * @param packagingUnit/{@code packagingSize} the same per-request pack EXTENSION {@link
     *     #resolveEntry} itself takes - null when the buyer hasn't had to define one, or the
     *     conversion they just supplied for a unit their chosen product doesn't already accept.
     */
    public int toStockUnitQuantity(
            Product product, int quantity, String unit, String packagingUnit, BigDecimal packagingSize) {
        return resolveEntry(product, quantity, null, unit, packagingUnit, packagingSize).baseQuantity();
    }

    /** {@link #toStockUnitQuantity(Product, int, String, String, BigDecimal)} with no pack extension. */
    public int toStockUnitQuantity(Product product, int quantity, String unit) {
        return toStockUnitQuantity(product, quantity, unit, null, null);
    }

    /**
     * Stock-in, stamped with the import that caused it - the one addition BULK_IMPORT_DESIGN.md
     * section 8.2 asks for ("the only additions are the batch transaction and the
     * {@code import_batch_id} stamp"), and deliberately the only change this class needed to
     * serve bulk stock-in. Unit conversion, vendor resolution, ProductVendor creation for a new
     * pairing, the cached rollups and the weighted-average cost recalculation are all reused
     * exactly as they are; section 8.2 is explicit that none of it is to be reimplemented.
     *
     * <p>An overload rather than a field on {@link StockInRequest} because the stamp is
     * provenance, not input: it is written by the engine that owns the batch, and putting it on
     * the request DTO would make it forgeable by any caller of the HTTP endpoint. It is also not
     * settable after the fact - {@code StockMovement.importBatchId} is {@code updatable = false},
     * so a movement's provenance is fixed at insert and cannot be quietly re-attributed later,
     * which is exactly what makes the undo in section 6.6 trustworthy.
     *
     * <h2>V21: the unit basis of every number this method touches</h2>
     * {@code request.quantity()} and {@code request.unitPrice()} are both expressed in whatever
     * {@code request.unit()} names - bags, tonnes, kg - and BOTH are converted by the one factor
     * {@link #resolveEntry} resolves, never one without the other. What is written is therefore:
     * {@code StockMovement.quantity} and {@code Product.quantityOnHand} in the product's stock
     * unit; {@code StockMovement.unitPriceAtTime}, {@code Product.costPrice} and
     * {@code ProductVendor.lastCostPrice} as money per ONE stock unit; and the entry as typed,
     * untouched, on the movement's {@code entered*} columns for display only. See
     * UNIT_UX_CONTRACT.md sections 3.1-3.3.
     *
     * @param importBatchId the {@code import_sessions.id} this receipt belongs to, or null for
     *     every ordinary hand-entered stock-in.
     */
    @Transactional
    public StockMutationResponse stockIn(
            UUID productId, StockInRequest request, UUID actingUserId, UUID importBatchId) {
        Product product = lockProductOrThrow(productId);
        UUID tenantId = requireTenantId();
        OffsetDateTime occurredAt = resolveOccurredAt(request.occurredAt());

        ResolvedEntry entry = resolveEntry(
                product,
                request.quantity(),
                request.unitPrice(),
                request.unit(),
                request.packagingUnit(),
                request.packagingSize());
        int quantityBaseUnits = entry.baseQuantity();

        if (request.companyVendorId() == null
                && productVendorRepository.countByClientIdAndProductId(tenantId, productId) > 0) {
            throw new CompanyVendorRequiredException();
        }

        boolean vendorIsNewToProduct = false;
        StockMutationResponse.CheaperVendorHint cheaperVendorHint = null;
        CompanyVendor companyVendor = null;
        if (request.companyVendorId() != null) {
            ProductVendorService.ReceiptResult receipt = productVendorService.findOrCreateForReceipt(
                    product,
                    request.companyVendorId(),
                    request.vendorSku(),
                    // Per STOCK UNIT, not per the unit typed - contract section 3.2. Passing
                    // request.unitPrice() here is exactly what made lastCostPrice mean "per bag"
                    // on one row and "per kg" on the next (P0-1/P0-2).
                    entry.basePrice(),
                    request.packagingUnit(),
                    request.packagingSize(),
                    quantityBaseUnits,
                    // Contract section 3.4 / non-negotiable 7: this delivery's pack becomes the
                    // supplier's standing default ONLY on an explicit opt-in. Absent means false.
                    request.savesAsSupplierDefault());
            vendorIsNewToProduct = receipt.vendorIsNewToProduct();
            companyVendor = receipt.vendor().getCompanyVendor();
            if (entry.basePrice() != null) {
                // Both sides of this comparison are now per stock unit: the candidate vendors'
                // tiers and lastCostPrice already were, and this receipt's price now is too.
                // Comparing a per-bag figure against per-kg ones is what made the hint fire
                // essentially at random before (P0-2).
                ProductVendorService.CheaperVendorHint hint = productVendorService.cheaperVendorHint(
                        productId, request.companyVendorId(), BigDecimal.valueOf(quantityBaseUnits), entry.basePrice());
                cheaperVendorHint = hint == null
                        ? null
                        : new StockMutationResponse.CheaperVendorHint(
                                hint.companyVendorId(), hint.companyVendorName(), hint.unitPrice(), hint.savingsPerUnit());
            }
        }

        product.setCostPrice(recomputeWeightedAverageCost(product, quantityBaseUnits, entry.basePrice()));
        product.setQuantityOnHand(product.getQuantityOnHand() + quantityBaseUnits);

        StockMovement movement = stockMovementRepository.save(StockMovement.builder()
                .product(product)
                .movementType(MovementType.IN)
                .quantity(quantityBaseUnits)
                .unitPriceAtTime(entry.basePrice())
                .note(request.note())
                .createdBy(reference(actingUserId))
                .companyVendor(companyVendor)
                .packagingUnit(request.packagingUnit())
                .packagingSize(request.packagingSize())
                .occurredAt(occurredAt)
                .importBatchId(importBatchId)
                .enteredUnit(entry.enteredUnit())
                .enteredQuantity(entry.enteredQuantity())
                .enteredUnitPrice(entry.enteredUnitPrice())
                .build());

        return StockMutationResponse.ofStockIn(product, movement, vendorIsNewToProduct, cheaperVendorHint);
    }

    /**
     * When the delivery happened. Null means now - the honest answer for a stock-in recorded as
     * it happens, and what every caller that predates V20 was implicitly saying.
     *
     * <h2>The day of grace is skew, not slack</h2>
     * A future delivery date is rejected, because a delivery cannot have arrived on a date that
     * has not happened yet and a forward-dated lot would sort last in FIFO forever - drawn from
     * never, while its stock sat on the books. But the boundary is tomorrow, not this instant,
     * and the day between them is clock and timezone skew rather than tolerance: a {@code
     * received_date} cell is a DATE, so it arrives as midnight in somebody's timezone, and a
     * user's own machine may be minutes or hours ahead of this server. Refusing those means
     * telling a user that today is in the future. What the rule actually exists to catch is a
     * mistyped year, and one day catches that exactly as well.
     *
     * <p>The other half of BULK_IMPORT_DESIGN.md section 8.4 - "warn, not block, beyond some
     * distance in the past" - is deliberately not here. That is a warning on a review row, which
     * is the import session's job to compose and the user's to dismiss; a stock-in service that
     * refused old dates would be refusing the very thing bulk stock-in exists to record. The
     * database's {@code chk_stock_movements_occurred_at_not_future} is the backstop under this
     * check, not a substitute for it - a raw CHECK violation names a column, and design doc 9.6
     * is explicit that a column name is never an error subject.
     */
    private OffsetDateTime resolveOccurredAt(OffsetDateTime requested) {
        if (requested == null) {
            return OffsetDateTime.now();
        }
        if (requested.isAfter(OffsetDateTime.now().plusDays(1))) {
            throw new FutureOccurredAtException();
        }
        return requested;
    }

    /**
     * {@code newCost = (oldQty x oldCost + inQty x inPrice) / (oldQty + inQty)}
     * (MULTI_VENDOR_INVENTORY_DESIGN.md section 5.3/6). When there is nothing to blend against
     * (no prior quantity, or no prior cost recorded) the new price simply becomes the cost -
     * dividing by zero would otherwise be undefined, and "the only price on record is what we
     * just paid" is the correct answer in that case anyway. When this delivery's own price is
     * absent (a stock-in recorded with no {@code unitPrice}, e.g. a free sample or a correction),
     * the prior cost is left untouched rather than blending a null in as zero, which would drag
     * the average down for no economic reason.
     *
     * <h2>Every one of these four numbers is per stock unit, and that is new</h2>
     * {@code product.getCostPrice()} and the returned value are money per ONE stock unit;
     * {@code inQuantityBaseUnits} and {@code product.getQuantityOnHand()} are counts of that same
     * unit. {@code inPriceBaseUnits} must therefore ALSO be per stock unit - it is
     * {@link ResolvedEntry#basePrice()}, never {@code request.unitPrice()}.
     *
     * <p>That parameter is the whole of P0-1 (UNIT_UX_REMEDIATION_PLAN.md section 3). It used to
     * receive the price exactly as typed while {@code inQuantityBaseUnits} arrived converted, so
     * a delivery of "20 bags at &#8358;45,000 per bag" blended &#8358;45,000 against 1,000 kg and
     * set the catalog cost price to &#8358;45,000 per kg - fifty times the truth, silently, and
     * then averaged into every later delivery so the error never washed out. The rename in this
     * signature is deliberate: the old name said nothing about basis, and the basis was the bug.
     *
     * <p>Blending is done at the incoming price's own scale and the result rounded to 2, the
     * column's scale - contract section 3.2's "do the arithmetic at scale 6 first, persist at
     * each column's own scale".
     */
    private BigDecimal recomputeWeightedAverageCost(Product product, int inQuantityBaseUnits, BigDecimal inPriceBaseUnits) {
        if (inPriceBaseUnits == null) {
            return product.getCostPrice();
        }
        int oldQty = product.getQuantityOnHand();
        BigDecimal oldCost = product.getCostPrice();
        if (oldQty <= 0 || oldCost == null) {
            return inPriceBaseUnits;
        }
        BigDecimal oldValue = oldCost.multiply(BigDecimal.valueOf(oldQty));
        BigDecimal inValue = inPriceBaseUnits.multiply(BigDecimal.valueOf(inQuantityBaseUnits));
        BigDecimal totalQty = BigDecimal.valueOf((long) oldQty + inQuantityBaseUnits);
        return oldValue.add(inValue).divide(totalQty, 2, RoundingMode.HALF_UP);
    }

    /**
     * Simple by default, advanced on request (design doc section 6/8). When {@code
     * request.allocations()} is null/empty, the candidate lots are drawn oldest-first via {@link
     * #resolveFifoAllocations}; when present, {@link #resolveManualAllocations} validates and
     * uses exactly those lot/quantity pairs instead. Either way, every candidate {@code IN}
     * movement for this product is row-locked first via {@link
     * StockMovementRepository#findInMovementsForUpdate} - see that method's javadoc for why the
     * lock has to happen before any remaining-balance number is trusted.
     *
     * <h2>V21: the unit basis of every number here</h2>
     * {@code request.quantity()} and {@code request.unitPrice()} are in whatever
     * {@code request.unit()} names and are both converted by the same factor
     * ({@link #resolveEntry}); {@code request.allocations()[].quantity} is - and stays - in the
     * product's STOCK unit with no unit of its own, because a lot's remaining balance is a
     * base-unit figure and rounding each line separately could not be made to sum back to the
     * request's own total (see {@code StockOutRequest}'s javadoc). No pack override is accepted
     * and none ever was: which supplier's stock a sale draws from is unknown until FIFO resolves
     * it, so stock-out can only offer the PRODUCT's units (design doc section 5.3).
     */
    @Transactional
    public StockMutationResponse stockOut(UUID productId, StockOutRequest request, UUID actingUserId) {
        Product product = lockProductOrThrow(productId);
        UUID tenantId = requireTenantId();

        ResolvedEntry entry =
                resolveEntry(product, request.quantity(), request.unitPrice(), request.unit(), null, null);
        int quantityBaseUnits = entry.baseQuantity();

        // The authoritative ceiling is still Product.quantityOnHand - see the class javadoc's
        // "cached counter, lot ledger is the source of truth underneath it, not a replacement"
        // and the reasoning below for why the ledger alone cannot be trusted as the ceiling yet.
        if (quantityBaseUnits > product.getQuantityOnHand()) {
            throw new InsufficientStockException(product.getQuantityOnHand(), quantityBaseUnits, unitSymbol(product));
        }

        List<StockMovement> lockedLotsOldestFirst = stockMovementRepository.findInMovementsForUpdate(productId, tenantId);

        List<LotDraw> draws = (request.allocations() == null || request.allocations().isEmpty())
                ? resolveFifoAllocations(lockedLotsOldestFirst, quantityBaseUnits)
                : resolveManualAllocations(
                        lockedLotsOldestFirst, request.allocations(), quantityBaseUnits, unitSymbol(product));

        product.setQuantityOnHand(product.getQuantityOnHand() - quantityBaseUnits);

        StockMovement outMovement = stockMovementRepository.save(StockMovement.builder()
                .product(product)
                .movementType(MovementType.OUT)
                .quantity(quantityBaseUnits)
                .unitPriceAtTime(entry.basePrice())
                .note(request.note())
                .createdBy(reference(actingUserId))
                .enteredUnit(entry.enteredUnit())
                .enteredQuantity(entry.enteredQuantity())
                .enteredUnitPrice(entry.enteredUnitPrice())
                .build());

        List<StockMutationResponse.AllocationBreakdown> breakdown = new ArrayList<>();
        for (LotDraw draw : draws) {
            stockMovementAllocationRepository.save(StockMovementAllocation.builder()
                    .outMovement(outMovement)
                    .inMovement(draw.lot())
                    .quantity(draw.quantity())
                    .build());

            CompanyVendor lotVendor = draw.lot().getCompanyVendor();
            if (lotVendor != null) {
                productVendorRepository
                        .findByClientIdAndProductIdAndCompanyVendorId(tenantId, productId, lotVendor.getId())
                        .ifPresent(vendor -> productVendorService.recordAllocationDrawdown(vendor, draw.quantity()));
            }

            breakdown.add(new StockMutationResponse.AllocationBreakdown(
                    draw.lot().getId(),
                    lotVendor == null ? null : lotVendor.getId(),
                    lotVendor == null ? null : lotVendor.getName(),
                    draw.quantity(),
                    draw.lot().getCreatedAt(),
                    draw.lot().getOccurredAt(),
                    // Composed here, by the same method the lot picker's rows use, so the
                    // receipt names a delivery with the exact phrase the user clicked on.
                    ProductLotResponse.label(
                            draw.lot().getOccurredAt(), lotVendor == null ? null : lotVendor.getName())));
        }

        return StockMutationResponse.ofStockOut(product, outMovement, breakdown);
    }

    /**
     * Oldest-lot-first, across every vendor (design doc section 5.2a: "oldest IN movement
     * first, regardless of vendor, naturally producing oldest-vendor-first as a side effect").
     *
     * <h2>V20: "oldest" means oldest DELIVERY, not oldest data entry</h2>
     * The ordering lives in {@link StockMovementRepository#findInMovementsForUpdate}, which this
     * method consumes in the order it is handed - so the fix is one {@code ORDER BY} rather than
     * anything here - but the consequence belongs in this javadoc because this is where FIFO is
     * actually decided. Lots now arrive ordered by {@code (occurredAt, createdAt)} instead of
     * {@code createdAt} alone. Before V20 those were interchangeable only because nothing could
     * record a past event; bulk stock-in exists precisely to record past events
     * (BULK_IMPORT_DESIGN.md section 8.4), so under the old ordering every backdated delivery
     * sorted after stock that genuinely arrived later, and this loop consumed them in exactly the
     * wrong order - drawing from a February lot before a January one and then recording that
     * false answer permanently in {@link StockMovementAllocation}, which is the table the recall
     * and dispute traces read.
     *
     * <p>{@code createdAt} remains as the tiebreak, and remains the immutable audit fact of when
     * the row was written. See {@code StockMovement.occurredAt} for why a tiebreak is the common
     * case here rather than a rare one.
     *
     * <h2>Why this can return draws that sum to LESS than quantityNeeded</h2>
     * The overall oversell check already happened in {@link #stockOut} against {@code
     * Product.quantityOnHand}, not against the sum of lot balances here - deliberately. Every
     * product that already had stock before V19 (the migration that introduced {@code
     * StockMovement} lots carrying vendor/packaging context) has that quantity with ZERO
     * backing {@code IN} movements, because the V19 backfill populated {@code product_vendors}
     * but wrote no historical ledger rows - there is nothing honest it could have backdated
     * them to. It was also true, until V20, of any product whose {@code quantityOnHand} was set
     * directly by a bulk upload rather than through {@code stockIn} - that hole is now closed
     * (see {@code ProductManagementService.bulkUpload}, which routes an imported opening balance
     * through this service so it becomes a real lot), but every row imported BEFORE V20 still
     * carries the shortfall and the reconciliation tool that fixes them is design doc section
     * 12, Phase 3. Requiring full lot coverage would make
     * every such unit of pre-existing stock permanently unsellable the moment this shipped.
     * Instead: draw whatever lots genuinely exist, oldest first, and if that falls short of
     * {@code quantityNeeded}, the remainder is still sold (the OUT movement and the {@code
     * quantityOnHand} decrement cover the full amount) but simply has no lot to record an
     * allocation against - its origin was never known to the ledger in the first place, and
     * this method cannot invent a delivery that was never recorded. Going forward, any product
     * that only ever receives stock through {@code stockIn} accumulates full lot coverage and
     * this shortfall never occurs for it.
     */
    private List<LotDraw> resolveFifoAllocations(List<StockMovement> lockedLotsOldestFirst, int quantityNeeded) {
        List<LotDraw> draws = new ArrayList<>();
        int remainingNeeded = quantityNeeded;
        for (StockMovement lot : lockedLotsOldestFirst) {
            if (remainingNeeded <= 0) {
                break;
            }
            int remaining = lot.getQuantity() - stockMovementAllocationRepository.sumQuantityByInMovementId(lot.getId());
            if (remaining <= 0) {
                continue;
            }
            int drawn = Math.min(remaining, remainingNeeded);
            draws.add(new LotDraw(lot, drawn));
            remainingNeeded -= drawn;
        }
        return draws;
    }

    /**
     * The "Choose vendor / unit manually" path (design doc section 6): draws exactly the
     * caller-specified lots/quantities instead of computing FIFO. Every referenced movement
     * must be one of THIS product's locked IN movements (i.e. exist and belong to this product -
     * {@code lockedLotsOldestFirst} already came from a client_id + product_id-scoped, locked
     * query) and must have enough remaining balance; the allocations must sum to exactly the
     * requested quantity, since a manual list that under- or over-specifies the total has no
     * defined meaning.
     *
     * <h2>Units, and the ids that used to leak</h2>
     * Every quantity in this method - each {@code allocation.quantity()}, each lot's remaining
     * balance, {@code quantityNeeded} - is in the product's STOCK unit. {@code quantityNeeded}
     * arrives already converted from whatever the caller typed, which is why
     * {@code unitSymbol} is threaded in: a refusal has to state what its numbers are counted in,
     * or it recreates P1-4, where a client validated the same comparison in bags and the server
     * answered in kg.
     *
     * <p>The three refusals no longer name a lot by its {@code inMovementId}. That was P1-6 - a
     * UUID in a sentence a user reads, forbidden by contract non-negotiable 6, and useless
     * besides, since the id is not on the screen they are looking at. A lot is named by the date
     * and supplier its own picker row shows, composed by {@code ProductLotResponse.label}. See
     * {@link InvalidStockAllocationException}.
     *
     * @param unitSymbol the product's stock unit, short form ("kg"), for the refusal messages
     *     only - it has no effect on any arithmetic here.
     */
    private List<LotDraw> resolveManualAllocations(
            List<StockMovement> lockedLotsOldestFirst,
            List<StockOutRequest.Allocation> allocations,
            int quantityNeeded,
            String unitSymbol) {
        Map<UUID, StockMovement> lockedById = new LinkedHashMap<>();
        for (StockMovement lot : lockedLotsOldestFirst) {
            lockedById.put(lot.getId(), lot);
        }

        List<LotDraw> draws = new ArrayList<>();
        int total = 0;
        for (StockOutRequest.Allocation allocation : allocations) {
            StockMovement lot = lockedById.get(allocation.inMovementId());
            if (lot == null) {
                throw InvalidStockAllocationException.notThisProductsDelivery();
            }
            int remaining = lot.getQuantity() - stockMovementAllocationRepository.sumQuantityByInMovementId(lot.getId());
            if (allocation.quantity() > remaining) {
                CompanyVendor lotVendor = lot.getCompanyVendor();
                throw InvalidStockAllocationException.notEnoughInLot(
                        Math.max(0, remaining),
                        allocation.quantity(),
                        unitSymbol,
                        lot.getOccurredAt(),
                        lotVendor == null ? null : lotVendor.getName());
            }
            draws.add(new LotDraw(lot, allocation.quantity()));
            total += allocation.quantity();
        }
        if (total != quantityNeeded) {
            throw InvalidStockAllocationException.totalMismatch(total, quantityNeeded, unitSymbol);
        }
        return draws;
    }

    /** One lot a stock-out drew from and how much - the pre-persistence form of an allocation row. */
    private record LotDraw(StockMovement lot, int quantity) {
    }

    /**
     * <b>The one place a request's {@code unit} is interpreted.</b> Resolves it against this
     * product's closed unit set ({@code UnitOptions}, UNIT_UX_CONTRACT.md section 2.1) and
     * returns the entry converted once, by one factor, into everything the ledger needs:
     * a quantity in the product's stock unit, a price per ONE stock unit, and the three
     * display-only facts about what was actually typed.
     *
     * <h2>Why quantity and price are resolved together and not in two methods</h2>
     * Because they were, and that is P0-1. The old {@code resolveBaseQuantity} converted the
     * quantity and there was no counterpart for the price, so {@code unitPrice} travelled the
     * whole stack unconverted while the quantity underneath it did not - and every price surface
     * downstream had to guess what the number was "per", and they guessed differently
     * (UNIT_UX_REMEDIATION_PLAN.md section 1). Returning both from one call makes the two halves
     * structurally inseparable: there is no longer a way to convert one and forget the other,
     * because there is no longer a method that converts only one.
     *
     * <h2>Resolution, per contract section 3.1</h2>
     * <ul>
     *   <li>{@code unit} null or blank ⇒ the stock unit, factor 1. Byte-for-byte today's
     *       behaviour, which is contract non-negotiable 8 and the reason the existing callers,
     *       the marketplace receipt path and the whole existing test suite are untouched by
     *       this change.</li>
     *   <li>{@code unit} present ⇒ it must match a {@code code} in the set, case-insensitively.
     *       {@code baseQuantity = round(quantity × factorToStockUnit)}, HALF_UP, scale 0;
     *       {@code basePrice = enteredPrice / factorToStockUnit}, scale 6, HALF_UP.</li>
     *   <li>no match ⇒ 400 naming every valid option, because a rejection that does not say what
     *       WOULD have worked is the defect that was reported, not a fix for it.</li>
     *   <li>a conversion that rounds to zero ⇒ 400, never a silent 0 - recording "this delivery
     *       contained nothing" for a delivery somebody just typed is the same class of error as
     *       recording the wrong amount.</li>
     * </ul>
     *
     * <h2>The per-request pack EXTENDS the set rather than bypassing it</h2>
     * {@code requestPackagingUnit}/{@code requestPackagingSize} are this one delivery's pack
     * (only {@code stockIn} ever supplies them; {@code stockOut} passes null for both - design
     * doc section 5.3). They add one option to the set for this request and matching then
     * happens against the set exactly as it does for every other request - contract section 3.1.
     * The pre-V21 fallbacks are preserved deliberately, so a request that supplies only a size,
     * or only a unit, still resolves against the product's own other half exactly as it did
     * before: a request-level value wins over the product's standing default, because a
     * per-delivery snapshot is the more specific fact.
     *
     * @param quantity the number typed, counted in {@code unit}.
     * @param enteredPrice the price typed, per ONE {@code unit} - per bag when {@code unit} is
     *     BAG. Null is ordinary (a free sample, a correction) and stays null throughout.
     * @param unit which of this product's units the two numbers above are in; null/blank is the
     *     stock unit.
     */
    private ResolvedEntry resolveEntry(
            Product product,
            int quantity,
            BigDecimal enteredPrice,
            String unit,
            String requestPackagingUnit,
            BigDecimal requestPackagingSize) {

        // Pre-V21 fallback semantics, preserved exactly: a request-level packaging value wins,
        // and either half may be omitted so long as the product supplies the other.
        String overridePackagingUnit =
                requestPackagingUnit != null ? requestPackagingUnit : product.getPackagingUnit();
        BigDecimal overridePackagingSize =
                requestPackagingSize != null ? requestPackagingSize : product.getPackagingSize();
        List<UnitOption> options = requestPackagingUnit == null && requestPackagingSize == null
                ? UnitOptions.forProduct(product)
                : UnitOptions.extendedWith(
                        UnitOptions.forProduct(product),
                        overridePackagingUnit,
                        overridePackagingSize,
                        product.getUnitOfMeasure());

        UnitOption option = UnitOptions.resolve(options, unit)
                .orElseThrow(() -> InvalidStockUnitException.unknownUnit(product.getName(), unit, options));

        int baseQuantity = option.toStockUnitQuantity(quantity);
        if (baseQuantity == 0 && quantity != 0) {
            throw InvalidStockUnitException.roundsToZero(quantity, option, unitSymbol(product));
        }

        // Section 3.3: what was typed is kept ONLY when it differs from what is stored. An entry
        // made in the stock unit has nothing extra to say, and writing the stock unit's own code
        // into entered_unit would turn "the user chose a unit" into a fact we invented for them.
        boolean typedInAnotherUnit = !option.isStockUnit();
        return new ResolvedEntry(
                option,
                baseQuantity,
                option.toStockUnitPrice(enteredPrice),
                typedInAnotherUnit ? option.code() : null,
                typedInAnotherUnit ? BigDecimal.valueOf(quantity) : null,
                typedInAnotherUnit ? enteredPrice : null);
    }

    /**
     * One request's quantity and price, resolved against the product's unit set - the return of
     * {@link #resolveEntry}, and the only shape in which a converted entry travels through this
     * class.
     *
     * @param option which member of the set {@code unit} named. Its {@code factorToStockUnit} is
     *     the single number both conversions below were derived from.
     * @param baseQuantity the quantity in the product's STOCK unit - what
     *     {@code StockMovement.quantity} and {@code Product.quantityOnHand} are counted in.
     * @param basePrice money per ONE stock unit - what {@code StockMovement.unitPriceAtTime},
     *     {@code Product.costPrice}, {@code ProductVendor.lastCostPrice} and the cheaper-vendor
     *     comparison all consume, and the ONLY price figure any of them may see. Null when the
     *     entry carried no price.
     * @param enteredUnit the unit code as submitted, or null when the stock unit was used.
     * @param enteredQuantity the number typed, in {@code enteredUnit}; null with it.
     * @param enteredUnitPrice the price typed, per {@code enteredUnit}; null with it. Display
     *     only - contract section 3.3 forbids computing a balance or a cost from any of the last
     *     three.
     */
    private record ResolvedEntry(
            UnitOption option,
            int baseQuantity,
            BigDecimal basePrice,
            String enteredUnit,
            BigDecimal enteredQuantity,
            BigDecimal enteredUnitPrice) {
    }

    /**
     * A newQuantity equal to the current quantity_on_hand is a valid request
     * (the user re-submitted the count they already saw) but represents no
     * actual change, so it's a no-op: no ledger row is written (there'd be
     * nothing to audit, and quantity=0 isn't a valid ADJUSTMENT row - see
     * V4__relax_stock_movements_quantity_constraint.sql) and the response
     * simply echoes the unchanged product back.
     */
    @Transactional
    public StockMutationResponse adjust(UUID productId, StockAdjustmentRequest request, UUID actingUserId) {
        Product product = lockProductOrThrow(productId);
        int delta = request.newQuantity() - product.getQuantityOnHand();
        if (delta == 0) {
            return StockMutationResponse.of(product, null);
        }
        product.setQuantityOnHand(request.newQuantity());

        StockMovement movement = stockMovementRepository.save(StockMovement.builder()
                .product(product)
                .movementType(MovementType.ADJUSTMENT)
                .quantity(delta)
                .note(request.note())
                .createdBy(reference(actingUserId))
                .build());

        return StockMutationResponse.of(product, movement);
    }

    @Transactional(readOnly = true)
    public Page<StockMovementResponse> history(UUID productId, Pageable pageable) {
        UUID tenantId = requireTenantId();
        if (productRepository.findByIdAndClientId(productId, tenantId).isEmpty()) {
            throw new ProductNotFoundException();
        }
        return stockMovementRepository
                .findAll(StockMovementSpecifications.forTenant(tenantId, productId, null, null, null, null), pageable)
                .map(StockMovementResponse::from);
    }

    /**
     * The stock in/out report - every movement in the tenant, filtered and paged. "What did we
     * actually take in that cost that much", which is the question the dashboard's Stock In/Out
     * Value cards raise and could not answer.
     *
     * <p>Reads the same ledger the cards aggregate, over the same {@code occurredAt} range, so
     * the two reconcile by construction - see {@code StockMovementSpecifications.forTenant} for
     * the date-column choice and for the fetch joins that keep this one query per page rather
     * than one per row.
     */
    @Transactional(readOnly = true)
    public Page<StockMovementResponse> allMovements(
            OffsetDateTime from,
            OffsetDateTime to,
            MovementType movementType,
            UUID productId,
            UUID companyVendorId,
            Pageable pageable) {
        return stockMovementRepository
                .findAll(
                        StockMovementSpecifications.forTenant(
                                requireTenantId(), productId, companyVendorId, from, to, movementType),
                        pageable)
                .map(StockMovementResponse::from);
    }

    /**
     * What {@link #allMovements}' whole filtered set adds up to, summed in the database rather
     * than over the page the caller happens to be looking at - see
     * {@code StockMovementSummaryResponse}.
     *
     * <p>Takes the same six arguments as {@link #allMovements} minus the paging, deliberately:
     * the moment the two accept different filters, the footer starts contradicting the table.
     */
    @Transactional(readOnly = true)
    public StockMovementSummaryResponse movementSummary(
            OffsetDateTime from,
            OffsetDateTime to,
            MovementType movementType,
            UUID productId,
            UUID companyVendorId) {
        List<Object[]> rows = stockMovementRepository.summarise(
                requireTenantId(),
                productId,
                companyVendorId,
                movementType == null ? null : movementType.name(),
                from,
                to);
        // A conditional aggregate with no GROUP BY always returns exactly one row, zeroes
        // included, so an empty list here would mean the query stopped being an aggregate.
        // Answered as an all-zero summary rather than an exception: a report footer is not
        // worth a 500, and every branch below would read zero anyway.
        if (rows.isEmpty()) {
            return new StockMovementSummaryResponse(BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0, 0, 0, 0, 0);
        }
        Object[] row = rows.getFirst();
        return new StockMovementSummaryResponse(
                (BigDecimal) row[0],
                (BigDecimal) row[1],
                ((Number) row[2]).longValue(),
                ((Number) row[3]).longValue(),
                ((Number) row[4]).longValue(),
                ((Number) row[5]).longValue(),
                ((Number) row[6]).longValue(),
                ((Number) row[7]).longValue(),
                ((Number) row[8]).longValue());
    }

    /**
     * {@code GET /api/stock-movements/{inMovementId}/allocations} - "what did this delivery go
     * on to fund", the recall/dispute trace MULTI_VENDOR_INVENTORY_DESIGN.md section 8/10 exists
     * to answer. Tenant-scoped the same way {@link #history} is: a movement that does not exist,
     * belongs to another tenant, or is not an {@code IN} movement (nothing was ever allocated
     * FROM an OUT/ADJUSTMENT row - there is nothing to trace forward) all answer 404 alike,
     * never leaking whether the id exists at all.
     */
    @Transactional(readOnly = true)
    public List<AllocationResponse> allocationsForInMovement(UUID inMovementId) {
        StockMovement inMovement = stockMovementRepository
                .findByIdAndClientId(inMovementId, requireTenantId())
                .filter(movement -> movement.getMovementType() == MovementType.IN)
                .orElseThrow(StockMovementNotFoundException::new);
        return stockMovementAllocationRepository.findAllByInMovementIdOrderByCreatedAtAsc(inMovement.getId()).stream()
                .map(AllocationResponse::from)
                .toList();
    }

    /**
     * The product's stock unit written the way a person writes it after a number - {@code "kg"},
     * not {@code "Kilogram (kg)"} and not {@code "KG"} - for the error messages of
     * {@link InsufficientStockException}, {@link InvalidStockAllocationException} and
     * {@link InvalidStockUnitException}. Empty string when the product has no unit configured;
     * each exception falls back to "units" from there.
     *
     * <h2>Why the message copy changed with it</h2>
     * These messages used to splice in the full catalog label, producing "Only 34 Kilogram (kg)
     * available" - which is a picker label dropped into a sentence, the same category of tell as
     * a capitalised unit mid-clause. Contract non-negotiable 2 requires every quantity shown to
     * state its unit; it does not require it to be stated in our vocabulary rather than the
     * reader's (BULK_IMPORT_DESIGN.md 9.6: "KG is our vocabulary, not the reader's").
     */
    private String unitSymbol(Product product) {
        return UnitOptions.symbolOf(product.getUnitOfMeasure());
    }

    /**
     * {@code GET /api/products/{productId}/lots} - the open deliveries a stock-out can draw
     * from, UNIT_UX_CONTRACT.md section 4. Ordered {@code (occurredAt, createdAt)}: the exact
     * FIFO order {@link #resolveFifoAllocations} consumes in, so the list a user is shown is the
     * list the system would have picked from itself, in the same order.
     *
     * <h2>Why an endpoint rather than another read of history</h2>
     * UNIT_UX_REMEDIATION_PLAN.md section 3, P1-5: the lot picker was built on the first 50 rows
     * of movement history filtered to {@code IN}. That shows fully-consumed lots as though they
     * were available, shows no remaining quantity on any row, and silently omits every lot past
     * page 50 - so the user picked blind and was answered with a 409 from a screen that had just
     * told them the lot was there. A remaining balance is derived (lot quantity minus the
     * allocations against it - MULTI_VENDOR_INVENTORY_DESIGN.md section 5.2a) and no page of
     * history carries it. Deriving it client-side would mean shipping the allocation ledger to
     * the browser; deriving it here is one grouped query.
     *
     * <h2>Units</h2>
     * {@code quantity} and {@code remaining} are base units; {@code unitPriceAtTime} is money per
     * ONE stock unit (contract section 3.2). Nothing is converted for display - a caller that
     * wants to show bags has the product's unit set and its factor.
     *
     * <h2>remaining is clamped at zero, and the clamp is not defensive noise</h2>
     * A lot's balance is {@code quantity - SUM(allocations)}, and that arithmetic can legitimately
     * be read while the true figure is in flux; more importantly, stock that predates V19 exists
     * with NO backing {@code IN} movements at all (see {@link #resolveFifoAllocations}), so lot
     * balances and {@code quantityOnHand} are not guaranteed to reconcile for older tenants. A
     * negative number in a picker is not a fact a user can do anything with, and section 4 pins
     * "never negative" for exactly that reason.
     *
     * <p>No pagination. The row set is one product's deliveries, which is small, and a picker
     * that paginated would reintroduce the "missed every lot past page 50" half of P1-5.
     *
     * @param openOnly filter to lots with something left in them. The default and the only
     *     setting a picker should use; {@code false} is for showing a full lot history.
     */
    @Transactional(readOnly = true)
    public List<ProductLotResponse> lots(UUID productId, boolean openOnly) {
        UUID tenantId = requireTenantId();
        if (productRepository.findByIdAndClientId(productId, tenantId).isEmpty()) {
            throw new ProductNotFoundException();
        }

        List<StockMovement> lotsOldestFirst = stockMovementRepository.findAll(
                StockMovementSpecifications.lotsForProduct(tenantId, productId),
                Sort.by(Sort.Direction.ASC, "occurredAt", "createdAt"));
        if (lotsOldestFirst.isEmpty()) {
            return List.of();
        }

        // One grouped query for every lot's consumed total, not one per row: a picker opening on
        // a product with two hundred deliveries would otherwise cost two hundred round trips.
        Map<UUID, Integer> allocatedByLot = new HashMap<>();
        for (Object[] row : stockMovementAllocationRepository.sumQuantityByInMovementIds(
                lotsOldestFirst.stream().map(StockMovement::getId).toList())) {
            allocatedByLot.put((UUID) row[0], ((Number) row[1]).intValue());
        }

        List<ProductLotResponse> response = new ArrayList<>();
        for (StockMovement lot : lotsOldestFirst) {
            int remaining = Math.max(0, lot.getQuantity() - allocatedByLot.getOrDefault(lot.getId(), 0));
            if (openOnly && remaining <= 0) {
                continue;
            }
            CompanyVendor vendor = lot.getCompanyVendor();
            String vendorName = vendor == null ? null : vendor.getName();
            response.add(new ProductLotResponse(
                    lot.getId(),
                    lot.getOccurredAt(),
                    vendor == null ? null : vendor.getId(),
                    vendorName,
                    lot.getQuantity(),
                    remaining,
                    lot.getUnitPriceAtTime(),
                    ProductLotResponse.label(lot.getOccurredAt(), vendorName)));
        }
        return List.copyOf(response);
    }

    private Product lockProductOrThrow(UUID productId) {
        return productRepository
                .findByIdAndClientIdForUpdate(productId, requireTenantId())
                .orElseThrow(ProductNotFoundException::new);
    }

    private User reference(UUID userId) {
        return userId == null ? null : userRepository.getReferenceById(userId);
    }

    private UUID requireTenantId() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context set");
        }
        return tenantId;
    }
}
