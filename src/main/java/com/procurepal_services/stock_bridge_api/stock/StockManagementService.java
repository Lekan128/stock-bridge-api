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
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductVendorRepository;
import com.procurepal_services.stock_bridge_api.repository.StockMovementAllocationRepository;
import com.procurepal_services.stock_bridge_api.repository.StockMovementRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import com.procurepal_services.stock_bridge_api.stock.dto.AllocationResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockAdjustmentRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.StockInRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMovementResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMutationResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockOutRequest;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
     * @param importBatchId the {@code import_sessions.id} this receipt belongs to, or null for
     *     every ordinary hand-entered stock-in.
     */
    @Transactional
    public StockMutationResponse stockIn(
            UUID productId, StockInRequest request, UUID actingUserId, UUID importBatchId) {
        Product product = lockProductOrThrow(productId);
        UUID tenantId = requireTenantId();
        OffsetDateTime occurredAt = resolveOccurredAt(request.occurredAt());

        int quantityBaseUnits =
                resolveBaseQuantity(product, request.quantity(), request.unit(), request.packagingUnit(), request.packagingSize());

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
                    null,
                    request.unitPrice(),
                    request.packagingUnit(),
                    request.packagingSize(),
                    quantityBaseUnits);
            vendorIsNewToProduct = receipt.vendorIsNewToProduct();
            companyVendor = receipt.vendor().getCompanyVendor();
            if (request.unitPrice() != null) {
                ProductVendorService.CheaperVendorHint hint = productVendorService.cheaperVendorHint(
                        productId, request.companyVendorId(), BigDecimal.valueOf(quantityBaseUnits), request.unitPrice());
                cheaperVendorHint = hint == null
                        ? null
                        : new StockMutationResponse.CheaperVendorHint(
                                hint.companyVendorId(), hint.companyVendorName(), hint.unitPrice(), hint.savingsPerUnit());
            }
        }

        product.setCostPrice(recomputeWeightedAverageCost(product, quantityBaseUnits, request.unitPrice()));
        product.setQuantityOnHand(product.getQuantityOnHand() + quantityBaseUnits);

        StockMovement movement = stockMovementRepository.save(StockMovement.builder()
                .product(product)
                .movementType(MovementType.IN)
                .quantity(quantityBaseUnits)
                .unitPriceAtTime(request.unitPrice())
                .note(request.note())
                .createdBy(reference(actingUserId))
                .companyVendor(companyVendor)
                .packagingUnit(request.packagingUnit())
                .packagingSize(request.packagingSize())
                .occurredAt(occurredAt)
                .importBatchId(importBatchId)
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
     */
    private BigDecimal recomputeWeightedAverageCost(Product product, int inQuantityBaseUnits, BigDecimal inPrice) {
        if (inPrice == null) {
            return product.getCostPrice();
        }
        int oldQty = product.getQuantityOnHand();
        BigDecimal oldCost = product.getCostPrice();
        if (oldQty <= 0 || oldCost == null) {
            return inPrice;
        }
        BigDecimal oldValue = oldCost.multiply(BigDecimal.valueOf(oldQty));
        BigDecimal inValue = inPrice.multiply(BigDecimal.valueOf(inQuantityBaseUnits));
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
     */
    @Transactional
    public StockMutationResponse stockOut(UUID productId, StockOutRequest request, UUID actingUserId) {
        Product product = lockProductOrThrow(productId);
        UUID tenantId = requireTenantId();

        int quantityBaseUnits = resolveBaseQuantity(product, request.quantity(), request.unit(), null, null);

        // The authoritative ceiling is still Product.quantityOnHand - see the class javadoc's
        // "cached counter, lot ledger is the source of truth underneath it, not a replacement"
        // and the reasoning below for why the ledger alone cannot be trusted as the ceiling yet.
        if (quantityBaseUnits > product.getQuantityOnHand()) {
            throw new InsufficientStockException(product.getQuantityOnHand(), quantityBaseUnits, unitLabel(product));
        }

        List<StockMovement> lockedLotsOldestFirst = stockMovementRepository.findInMovementsForUpdate(productId, tenantId);

        List<LotDraw> draws = (request.allocations() == null || request.allocations().isEmpty())
                ? resolveFifoAllocations(lockedLotsOldestFirst, quantityBaseUnits)
                : resolveManualAllocations(lockedLotsOldestFirst, request.allocations(), quantityBaseUnits);

        product.setQuantityOnHand(product.getQuantityOnHand() - quantityBaseUnits);

        StockMovement outMovement = stockMovementRepository.save(StockMovement.builder()
                .product(product)
                .movementType(MovementType.OUT)
                .quantity(quantityBaseUnits)
                .unitPriceAtTime(request.unitPrice())
                .note(request.note())
                .createdBy(reference(actingUserId))
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
                    draw.lot().getOccurredAt()));
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
     */
    private List<LotDraw> resolveManualAllocations(
            List<StockMovement> lockedLotsOldestFirst, List<StockOutRequest.Allocation> allocations, int quantityNeeded) {
        Map<UUID, StockMovement> lockedById = new LinkedHashMap<>();
        for (StockMovement lot : lockedLotsOldestFirst) {
            lockedById.put(lot.getId(), lot);
        }

        List<LotDraw> draws = new ArrayList<>();
        int total = 0;
        for (StockOutRequest.Allocation allocation : allocations) {
            StockMovement lot = lockedById.get(allocation.inMovementId());
            if (lot == null) {
                throw new InvalidStockAllocationException(
                        "inMovementId " + allocation.inMovementId() + " is not a delivery of this product.");
            }
            int remaining = lot.getQuantity() - stockMovementAllocationRepository.sumQuantityByInMovementId(lot.getId());
            if (allocation.quantity() > remaining) {
                throw new InvalidStockAllocationException("Only " + remaining + " remaining from that delivery ("
                        + lot.getId() + "), " + allocation.quantity() + " requested.");
            }
            draws.add(new LotDraw(lot, allocation.quantity()));
            total += allocation.quantity();
        }
        if (total != quantityNeeded) {
            throw new InvalidStockAllocationException(
                    "allocations sum to " + total + " but the requested quantity is " + quantityNeeded + ".");
        }
        return draws;
    }

    /** One lot a stock-out drew from and how much - the pre-persistence form of an allocation row. */
    private record LotDraw(StockMovement lot, int quantity) {
    }

    /**
     * {@code unit} names either the product's base {@code unitOfMeasure} (no conversion) or its
     * packaging unit (multiply by packaging size to reach base units) - design doc section 6's
     * "kg <-> bag" toggle. {@code requestPackagingUnit}/{@code requestPackagingSize} are this
     * specific delivery's own snapshot (only ever supplied by {@code stockIn}; {@code stockOut}
     * always passes null for both, since which vendor a sale will draw from - and therefore
     * which packaging - is not known until FIFO resolves it, so stock-out's toggle can only ever
     * read the PRODUCT's own default - design doc section 5.3). Either way, a request-level
     * value wins over the product's own packagingUnit/packagingSize when both are present, since
     * a per-delivery snapshot is more specific than the product's standing default.
     */
    private int resolveBaseQuantity(
            Product product, int quantity, String unit, String requestPackagingUnit, BigDecimal requestPackagingSize) {
        if (unit == null || unit.isBlank() || unit.equalsIgnoreCase(product.getUnitOfMeasure())) {
            return quantity;
        }
        String packagingUnit = requestPackagingUnit != null ? requestPackagingUnit : product.getPackagingUnit();
        BigDecimal packagingSize = requestPackagingSize != null ? requestPackagingSize : product.getPackagingSize();
        if (packagingUnit == null || packagingSize == null || !unit.equalsIgnoreCase(packagingUnit)) {
            throw new InvalidStockUnitException(unit);
        }
        return packagingSize.multiply(BigDecimal.valueOf(quantity)).setScale(0, RoundingMode.HALF_UP).intValueExact();
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
                .findAll(StockMovementSpecifications.forTenant(tenantId, productId, null, null, null), pageable)
                .map(StockMovementResponse::from);
    }

    @Transactional(readOnly = true)
    public Page<StockMovementResponse> allMovements(
            OffsetDateTime from, OffsetDateTime to, MovementType movementType, Pageable pageable) {
        return stockMovementRepository
                .findAll(StockMovementSpecifications.forTenant(requireTenantId(), null, from, to, movementType), pageable)
                .map(StockMovementResponse::from);
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
     * A human-readable label for the product's base unit ("Kilogram (kg)"), for {@link
     * InsufficientStockException}'s message only - see that field's own javadoc for why this is
     * a nice-to-have rather than load-bearing. Falls back to the raw stored code, or null (the
     * exception itself falls back further, to "units") when the product has no unit configured
     * at all.
     */
    private String unitLabel(Product product) {
        if (product.getUnitOfMeasure() == null) {
            return null;
        }
        return UnitOfMeasure.fromCode(product.getUnitOfMeasure())
                .map(UnitOfMeasure::label)
                .orElse(product.getUnitOfMeasure());
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
