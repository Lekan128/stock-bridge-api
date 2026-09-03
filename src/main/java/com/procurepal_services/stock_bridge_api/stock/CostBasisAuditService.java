package com.procurepal_services.stock_bridge_api.stock;

import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.entity.ProductVendor;
import com.procurepal_services.stock_bridge_api.imports.ImportCopy;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOptions;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductVendorRepository;
import com.procurepal_services.stock_bridge_api.repository.StockMovementRepository;
import com.procurepal_services.stock_bridge_api.stock.dto.CostBasisAnomalyResponse;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one-off cost-basis audit UNIT_UX_REMEDIATION_PLAN.md Phase 0 asks for: find the products
 * whose stored cost was written through the P0-1 path, where a price entered per PACK was
 * recorded and averaged as though it were per stock unit.
 *
 * <h2>Read-only, and deliberately so</h2>
 * Nothing in this class writes. Phase 0 says why in one sentence - "existing {@code
 * lastCostPrice} and {@code costPrice} values written through the broken path cannot be
 * distinguished from correct ones after the fact [...] Do not attempt an automatic fix" - and the
 * reason that is true rather than merely cautious is that the distinguishing fact was the one the
 * bug destroyed. Which unit a human typed a price in was never stored until V21's {@code
 * entered_unit}; a &#8358;45,000 row is a &#8358;45,000 row whether the product is rice priced by
 * the bag or a reagent priced by the gram. An automatic repair would therefore be a guess applied
 * to money, at scale, with no way for anyone to tell afterwards which rows it had guessed on. So
 * this gathers evidence and hands it to a human who knows what the product is.
 *
 * <h2>Three signals, because the plan's stated one cannot see the worst case</h2>
 * Phase 0's stated heuristic is "products whose {@code costPrice} differs from the median
 * {@code unitPriceAtTime} of their movements by more than an order of magnitude". That is
 * {@link #SIGNAL_COST_DISAGREES_WITH_DELIVERIES}, and it is genuinely useful - but on its own it
 * has a blind spot large enough to matter, and it is worth naming precisely.
 *
 * <p>The old code stored the raw entered price in {@code unit_price_at_time} AND blended that
 * same raw number into {@code cost_price}. Both were inflated by the same factor, at the same
 * moment. So a product every one of whose deliveries came through the broken path has a
 * {@code costPrice} that <em>agrees perfectly</em> with the median of its own movements - both
 * wrong, both wrong by the same 50&times; - and a comparison between them finds a ratio of 1.0
 * and reports nothing. The stated heuristic only fires on a history that MIXES broken and correct
 * entries, which is the milder half of the damage.
 *
 * <p>{@link #SIGNAL_COST_EXCEEDS_SELLING_PRICE} asks an economic question instead - nobody stocks
 * an item whose cost is ten times what they sell it for - and survives the bug because
 * {@code Product.unitPrice} was never touched by it. But it is silent for a buying company:
 * {@code ProductManagementService} discards {@code unitPrice} for any tenant that is not a
 * seller, so a wholesaler's private stock has {@code unitPrice} null on every row. That is
 * exactly this product's primary audience, so on its own this signal would leave the main case
 * unreported.
 *
 * <h2>Why the third signal is provenance rather than statistics</h2>
 * Both rules above compare a number against another number, and a uniformly-damaged product with
 * no selling price offers no honest number to compare against. Phase 0 says as much - these
 * values "cannot be distinguished from correct ones after the fact" - and it is right: no amount
 * of arithmetic over the ledger alone can separate rice wrongly recorded at &#8358;45,000/kg from
 * a reagent that genuinely costs that.
 *
 * <p>{@link #SIGNAL_ENTERED_IN_PACKS_BEFORE_FIX} therefore stops asking what the numbers look
 * like and asks <em>how the row was written</em>. A movement that carries a pack snapshot
 * ({@code packaging_size}) but no {@code entered_unit} is, by construction, a delivery that was
 * described in packs and recorded before V21 existed to record which unit was typed - which is
 * precisely the path whose price field the old modal labelled "per {pack}" and then stored raw.
 * That is a fact about the row's history, not an inference about its value, so it holds however
 * consistent the damaged numbers are with each other.
 *
 * <p>It is a flag, not a verdict, and it over-reports on purpose: the old code snapshotted the
 * pack whether or not the price was typed per pack, so some flagged rows will turn out fine. For
 * a one-off audit whose output a human reads product by product, a false positive costs a glance
 * at a delivery note and a false negative costs a permanently wrong cost price. The evidence that
 * separates them - {@code ratioMatchesPackSize} - is reported alongside.
 *
 * <h2>What a match is not</h2>
 * Neither signal is proof, and the response type says so. The strongest evidence available is
 * {@code ratioMatchesPackSize}: an inflation factor sitting within 10% of the number of stock
 * units in the product's own pack is a per-pack price in a per-stock-unit column, and is hard to
 * arrive at by honest pricing. Even that is evidence, not a verdict.
 */
@Service
@RequiredArgsConstructor
public class CostBasisAuditService {

    /**
     * Phase 0's stated rule: the stored cost and the deliveries behind it disagree by more than
     * an order of magnitude. Fires on a product whose history mixes entries made through the
     * broken path with entries made correctly - see the class javadoc for why a product whose
     * history is uniformly broken does NOT trip this one.
     */
    public static final String SIGNAL_COST_DISAGREES_WITH_DELIVERIES = "COST_DISAGREES_WITH_DELIVERIES";

    /**
     * The stored cost is at least an order of magnitude above what the product SELLS for. Catches
     * the case the rule above cannot: a product every one of whose deliveries was entered per
     * pack, where the cost and the movement prices are consistently, identically wrong.
     *
     * <p>Silent for a product with no {@code unitPrice}, which is ordinary for a buying company's
     * private stock (nullable since V17). That is a gap in coverage rather than a clean bill of
     * health, and it is why the two signals are reported by name instead of collapsed into one
     * boolean.
     */
    public static final String SIGNAL_COST_EXCEEDS_SELLING_PRICE = "COST_EXCEEDS_SELLING_PRICE";

    /**
     * At least one {@code IN} movement for this product was recorded through the pre-V21 path
     * while being described in packs: it carries a {@code packaging_size} snapshot and no
     * {@code entered_unit}. That is the exact provenance of a price the old stock-in modal
     * collected under a "per {pack}" label and then stored in a per-stock-unit column.
     *
     * <p>The only signal of the three that works for a buying company with no selling price and a
     * uniformly damaged history - which is the main case. See the class javadoc for why it is a
     * provenance check rather than a comparison, and why it deliberately over-reports.
     */
    public static final String SIGNAL_ENTERED_IN_PACKS_BEFORE_FIX = "ENTERED_IN_PACKS_BEFORE_FIX";

    /** "More than an order of magnitude" - Phase 0's own words, as a number. */
    private static final BigDecimal ORDER_OF_MAGNITUDE = BigDecimal.TEN;

    /** How close a ratio must sit to the pack size to call it a match. 10% either way. */
    private static final BigDecimal PACK_MATCH_TOLERANCE = new BigDecimal("0.10");

    private final ProductRepository productRepository;
    private final ProductVendorRepository productVendorRepository;
    private final StockMovementRepository stockMovementRepository;

    /**
     * Every product in the calling tenant whose stored cost basis looks wrong, worst ratio first.
     *
     * <h2>Units</h2>
     * Every price compared here - {@code Product.costPrice}, {@code Product.unitPrice},
     * {@code StockMovement.unitPriceAtTime}, {@code ProductVendor.lastCostPrice} - is CLAIMED by
     * its column to be money per one of the product's stock units. Testing that claim is the
     * entire purpose of this method, so it treats every one of them as a bare number and reports
     * the ratios rather than trusting any of them.
     *
     * <p>Tenant-scoped like every other read in this service layer, via the same
     * {@code requireTenantId()} convention. A tenant only ever audits its own catalogue; this is
     * a report about a company's own money.
     *
     * <h2>Cost</h2>
     * Two queries plus one per FLAGGED product. The tenant's whole priced {@code IN} ledger and
     * its whole product list are each read once; supplier lines are then fetched only for the
     * products that already tripped a signal, which is a small set by construction - a report
     * whose fan-out scaled with the catalogue rather than with the damage would be unrunnable on
     * exactly the tenants that most need it.
     */
    @Transactional(readOnly = true)
    public List<CostBasisAnomalyResponse> costBasisAnomalies() {
        UUID tenantId = requireTenantId();

        Map<UUID, List<BigDecimal>> pricesByProduct = new HashMap<>();
        Map<UUID, Map<UUID, List<BigDecimal>>> pricesByProductAndVendor = new HashMap<>();
        Map<UUID, BigDecimal> packEnteredBeforeFixByProduct = new HashMap<>();
        for (Object[] row : stockMovementRepository.findPricedInMovementPricesForTenant(tenantId)) {
            UUID productId = (UUID) row[0];
            UUID companyVendorId = (UUID) row[1];
            BigDecimal price = (BigDecimal) row[2];
            BigDecimal moventPackagingSize = (BigDecimal) row[3];
            String enteredUnit = (String) row[4];

            pricesByProduct.computeIfAbsent(productId, key -> new ArrayList<>()).add(price);
            if (companyVendorId != null) {
                pricesByProductAndVendor
                        .computeIfAbsent(productId, key -> new HashMap<>())
                        .computeIfAbsent(companyVendorId, key -> new ArrayList<>())
                        .add(price);
            }
            // Provenance, not arithmetic: a pack snapshot with no entered_unit is a delivery
            // described in packs and written before V21 could record which unit was typed.
            if (moventPackagingSize != null && moventPackagingSize.signum() > 0 && enteredUnit == null) {
                packEnteredBeforeFixByProduct.put(productId, moventPackagingSize);
            }
        }

        List<CostBasisAnomalyResponse> findings = new ArrayList<>();
        for (Product product : productRepository.findAllByClientId(tenantId)) {
            if (product.getCostPrice() == null || product.getCostPrice().signum() <= 0) {
                // No cost on record is nothing to suspect. A zero cost is not the P0-1 shape
                // either - that bug inflates, it does not zero.
                continue;
            }
            CostBasisAnomalyResponse finding = assess(
                    product,
                    median(pricesByProduct.get(product.getId())),
                    pricesByProductAndVendor.getOrDefault(product.getId(), Map.of()),
                    packEnteredBeforeFixByProduct.get(product.getId()),
                    tenantId);
            if (finding != null) {
                findings.add(finding);
            }
        }

        // Worst measured ratio first; the provenance-only findings, which have nothing measured
        // to sort by, fall to the end rather than being dropped or pushed to the top.
        findings.sort(Comparator.comparing(
                        CostBasisAnomalyResponse::largestRatio, Comparator.nullsFirst(Comparator.naturalOrder()))
                .reversed());
        return List.copyOf(findings);
    }

    /**
     * Runs both signals against one product and composes its finding, or returns null when
     * neither fires. Split out so the two rules sit next to each other and neither can quietly
     * acquire a third meaning.
     */
    private CostBasisAnomalyResponse assess(
            Product product,
            BigDecimal medianMovementPrice,
            Map<UUID, List<BigDecimal>> vendorPrices,
            BigDecimal packSizeEnteredBeforeFix,
            UUID tenantId) {

        BigDecimal costPrice = product.getCostPrice();
        List<String> signals = new ArrayList<>();
        BigDecimal largestRatio = null;

        BigDecimal deliveryRatio = divergence(costPrice, medianMovementPrice);
        if (deliveryRatio != null && deliveryRatio.compareTo(ORDER_OF_MAGNITUDE) >= 0) {
            signals.add(SIGNAL_COST_DISAGREES_WITH_DELIVERIES);
            largestRatio = max(largestRatio, deliveryRatio);
        }

        // Deliberately one-directional, unlike the rule above. A cost far BELOW the selling price
        // is an ordinary healthy margin, not a defect; only a cost far above it is nonsense.
        BigDecimal sellingRatio = ratio(costPrice, product.getUnitPrice());
        if (sellingRatio != null && sellingRatio.compareTo(ORDER_OF_MAGNITUDE) >= 0) {
            signals.add(SIGNAL_COST_EXCEEDS_SELLING_PRICE);
            largestRatio = max(largestRatio, sellingRatio);
        }

        if (packSizeEnteredBeforeFix != null) {
            signals.add(SIGNAL_ENTERED_IN_PACKS_BEFORE_FIX);
        }

        if (signals.isEmpty()) {
            return null;
        }

        String stockUnitSymbol = UnitOptions.symbolOf(product.getUnitOfMeasure());
        // The product's CURRENT pack for context, falling back to the pack the suspect delivery
        // itself was snapshotted with - a product's packaging can have been edited since, and the
        // number worth comparing a ratio against is the one that was in force when the row was
        // written.
        BigDecimal packagingSize =
                product.getPackagingSize() != null ? product.getPackagingSize() : packSizeEnteredBeforeFix;
        boolean matchesPack = largestRatio != null && matchesPackSize(largestRatio, packagingSize);

        List<CostBasisAnomalyResponse.SuspectSupplierLine> suspectLines =
                suspectSupplierLines(product, vendorPrices, stockUnitSymbol, tenantId);

        return new CostBasisAnomalyResponse(
                product.getId(),
                product.getName(),
                product.getSku(),
                stockUnitSymbol,
                costPrice,
                medianMovementPrice,
                product.getUnitPrice(),
                packagingSize,
                largestRatio,
                matchesPack,
                List.copyOf(signals),
                summary(product, costPrice, stockUnitSymbol, largestRatio, packagingSize, matchesPack),
                suspectLines);
    }

    /** {@code max} that treats a missing ratio as "nothing measured yet" rather than as zero. */
    private BigDecimal max(BigDecimal current, BigDecimal candidate) {
        return current == null ? candidate : current.max(candidate);
    }

    /**
     * The finding as a sentence a human can act on, naming the product rather than a column
     * (BULK_IMPORT_DESIGN.md 9.6, and non-negotiable 6 for the absence of any id). It states what
     * is stored, how far out it looks, and - when the ratio matches the pack - what almost
     * certainly happened, in the vocabulary of section 1.
     *
     * <p>It stops short of telling anyone what the right number is. That is the whole posture of
     * Phase 0: this code does not know, and a sentence that guessed would be acted on as though
     * it did.
     */
    private String summary(
            Product product,
            BigDecimal costPrice,
            String stockUnitSymbol,
            BigDecimal largestRatio,
            BigDecimal packagingSize,
            boolean matchesPack) {
        String unit = stockUnitSymbol.isEmpty() ? "unit" : stockUnitSymbol;
        String subject = ImportCopy.subject(product.getName(), product.getSku());
        String stored = subject + " is recorded as costing " + ImportCopy.money(costPrice) + " per " + unit;
        String closer = " Check it against a delivery note before changing anything.";

        if (largestRatio == null) {
            // Provenance only: nothing about the number itself looks wrong, and saying it did
            // would be inventing evidence. What IS known is how the row was written.
            String pack = packagingSize == null
                    ? ""
                    : " one pack held " + ImportCopy.count(packagingSize) + " " + unit + ", so if that price was "
                            + "typed per pack the real figure is that many times smaller;";
            return stored + ", from a delivery that was entered in packs before we recorded which unit a price "
                    + "was typed in —" + pack + " we cannot tell from the record alone." + closer;
        }

        String head = stored + ", about " + ImportCopy.count(largestRatio.setScale(0, RoundingMode.HALF_UP))
                + " times what the rest of its history suggests.";
        if (matchesPack) {
            return head + " That is almost exactly the " + ImportCopy.count(packagingSize) + " " + unit
                    + " in one pack, so this price was most likely typed per pack and recorded per " + unit + "."
                    + closer;
        }
        return head + closer;
    }

    /**
     * The same suspicion applied to {@code ProductVendor.lastCostPrice}, which Phase 0 names
     * alongside {@code costPrice} because it was written by the same line of the same broken path
     * ({@code findOrCreateForReceipt} received the raw request price).
     *
     * <p>Only consulted for products that already tripped a signal - see
     * {@link #costBasisAnomalies}'s cost note. A supplier line is reported when its stored cost
     * disagrees with that supplier's OWN deliveries by an order of magnitude, or when it exceeds
     * the product's selling price by one; the same two rules, scoped to one supplier.
     */
    private List<CostBasisAnomalyResponse.SuspectSupplierLine> suspectSupplierLines(
            Product product, Map<UUID, List<BigDecimal>> vendorPrices, String stockUnitSymbol, UUID tenantId) {

        List<CostBasisAnomalyResponse.SuspectSupplierLine> lines = new ArrayList<>();
        for (ProductVendor vendor : productVendorRepository.findAllByClientIdAndProductId(tenantId, product.getId())) {
            BigDecimal lastCostPrice = vendor.getLastCostPrice();
            if (lastCostPrice == null || lastCostPrice.signum() <= 0) {
                continue;
            }
            BigDecimal vendorMedian = median(vendorPrices.get(vendor.getCompanyVendor().getId()));
            BigDecimal deliveryRatio = divergence(lastCostPrice, vendorMedian);
            BigDecimal sellingRatio = ratio(lastCostPrice, product.getUnitPrice());

            boolean suspect = (deliveryRatio != null && deliveryRatio.compareTo(ORDER_OF_MAGNITUDE) >= 0)
                    || (sellingRatio != null && sellingRatio.compareTo(ORDER_OF_MAGNITUDE) >= 0);
            if (!suspect) {
                continue;
            }
            String unit = stockUnitSymbol.isEmpty() ? "unit" : stockUnitSymbol;
            lines.add(new CostBasisAnomalyResponse.SuspectSupplierLine(
                    vendor.getId(),
                    vendor.getCompanyVendor().getName(),
                    lastCostPrice,
                    vendorMedian,
                    "We last recorded " + ImportCopy.money(lastCostPrice) + " per " + unit + " from "
                            + vendor.getCompanyVendor().getName()
                            + ", which looks like it was typed per pack rather than per " + unit + "."));
        }
        return List.copyOf(lines);
    }

    /**
     * How far apart two prices are, as a factor at least 1 whichever is larger - so a cost 50x
     * the median and a cost one-fiftieth of it both come back as 50. Null when there is nothing
     * to compare against, which is not the same as "they agree" and is why the caller checks for
     * null rather than treating a missing median as a pass.
     */
    private BigDecimal divergence(BigDecimal value, BigDecimal reference) {
        if (value == null || reference == null || reference.signum() <= 0 || value.signum() <= 0) {
            return null;
        }
        return value.compareTo(reference) >= 0 ? ratio(value, reference) : ratio(reference, value);
    }

    /** {@code numerator / denominator} at scale 4, or null when the comparison is not available. */
    private BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() <= 0) {
            return null;
        }
        return numerator.divide(denominator, 4, RoundingMode.HALF_UP);
    }

    /**
     * Whether an inflation factor lands within 10% of the number of stock units in one pack - the
     * strongest evidence this audit can offer, because it is the arithmetic signature of the bug
     * rather than a general smell. A pack of 50 that produced a 50x inflation is a per-bag price
     * sitting in a per-kg column.
     */
    private boolean matchesPackSize(BigDecimal ratio, BigDecimal packagingSize) {
        if (packagingSize == null || packagingSize.signum() <= 0 || ratio.signum() <= 0) {
            return false;
        }
        BigDecimal tolerance = packagingSize.multiply(PACK_MATCH_TOLERANCE);
        return ratio.subtract(packagingSize).abs().compareTo(tolerance) <= 0;
    }

    /**
     * The middle price, averaging the two middles for an even count. A median rather than a mean
     * deliberately: the damage being hunted is a small number of enormously inflated values, and
     * a mean would be dragged toward them by the very rows it is meant to be compared against -
     * the outlier would hide itself.
     */
    private BigDecimal median(List<BigDecimal> prices) {
        if (prices == null || prices.isEmpty()) {
            return null;
        }
        List<BigDecimal> sorted = new ArrayList<>(prices);
        sorted.sort(Comparator.naturalOrder());
        int size = sorted.size();
        if (size % 2 == 1) {
            return sorted.get(size / 2);
        }
        return sorted.get(size / 2 - 1)
                .add(sorted.get(size / 2))
                .divide(new BigDecimal("2"), 6, RoundingMode.HALF_UP);
    }

    private UUID requireTenantId() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context set");
        }
        return tenantId;
    }
}
