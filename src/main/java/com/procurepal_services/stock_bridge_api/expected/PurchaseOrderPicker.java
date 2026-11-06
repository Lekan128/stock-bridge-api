package com.procurepal_services.stock_bridge_api.expected;

import com.procurepal_services.stock_bridge_api.branch.BranchStockService;
import com.procurepal_services.stock_bridge_api.branch.dto.BranchFigure;
import com.procurepal_services.stock_bridge_api.entity.ExpectedDelivery;
import com.procurepal_services.stock_bridge_api.entity.ExpectedDeliveryLine;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.product.StockStatus;
import com.procurepal_services.stock_bridge_api.product.bulk.SheetUnitOptions;
import com.procurepal_services.stock_bridge_api.product.bulk.StockInTemplateFilter;
import com.procurepal_services.stock_bridge_api.product.bulk.StockInTemplateRow;
import com.procurepal_services.stock_bridge_api.product.bulk.StockInTemplateService;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOptions;
import com.procurepal_services.stock_bridge_api.repository.ExpectedDeliveryRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "What do you want?" on a new purchase order, with what decides "how many?" beside each line
 * (redesign of 2026-11-04): how much is left, whether that is low, what is already coming on other
 * orders, and what was ordered from this supplier last time.
 *
 * <p>Every figure is said in the line's own unit ("3 bags left"), converted from the stock unit with
 * the same factor a delivery uses, so the number beside the box counts the same things the box does.
 * Stock is the branch the order is going to, or the company's at a one-branch company (Pattern D).
 */
@Service
@RequiredArgsConstructor
public class PurchaseOrderPicker {

    /** The most "running low" lines one supplier's list shows. */
    static final int RUNNING_LOW_LIMIT = 20;

    private final StockInTemplateService stockInTemplateService;
    private final ProductRepository productRepository;
    private final ExpectedDeliveryRepository expectedDeliveryRepository;
    private final BranchStockService branchStockService;
    private final com.procurepal_services.stock_bridge_api.repository.StockMovementRepository stockMovementRepository;

    /**
     * One way of buying one product.
     *
     * @param left how many of this unit are in stock, rounded down to one decimal.
     * @param onHand what is in stock in the product's own unit, exactly - so a screen can say
     *     "12 bags + 100 g" rather than "12.1 bags".
     * @param perUnit how many of the product's own unit one of this is (50 for a 50 kg bag; 1 loose).
     * @param stockState OK, LOW or OUT against the alert level; absent when there is no level and
     *     some is left.
     * @param coming how many of this unit are still owed on open orders; absent when none.
     * @param lastOrdered how many were ordered from this supplier last time, in this unit; absent
     *     when the last order used another unit or there was none.
     * @param usual the way this product is normally bought from this supplier - the unit a line
     *     starts in. Exactly one line per product has it.
     * @param lastPrice what one of this unit cost from this supplier last time - the price a line's
     *     box starts with (plan section 11.4); absent when never priced.
     * @param lastPriceOn when that was.
     * @param lastPriceKind PAID (a delivery recorded at that price) or ORDERED (a purchase order agreed it).
     */
    public record Line(
            UUID productId,
            String productName,
            String sku,
            String unit,
            String comesIn,
            boolean pack,
            BigDecimal left,
            BigDecimal onHand,
            BigDecimal perUnit,
            StockStatus stockState,
            BigDecimal coming,
            BigDecimal lastOrdered,
            boolean usual,
            BigDecimal lastPrice,
            java.time.LocalDate lastPriceOn,
            String lastPriceKind) {
    }

    /** A supplier ordered from before, for the supplier search: when, and a few of what. */
    public record RecentSupplier(UUID vendorId, OffsetDateTime lastOrderedAt, List<String> products) {
    }

    /**
     * @param search name, code or barcode; with none, the supplier's own products.
     * @param runningLow only the supplier's products that are low or out at the branch.
     */
    @Transactional(readOnly = true)
    public List<Line> lines(String search, UUID vendorId, boolean runningLow, UUID branchId, int limit) {
        return lines(search, vendorId, runningLow, branchId, limit, List.of(), null);
    }

    /**
     * @param productIds these products exactly ("Order more", "Order again"); wins over the rest.
     * @param excludeOrderId the order being changed: its own lines are left out of "coming".
     */
    @Transactional(readOnly = true)
    public List<Line> lines(
            String search, UUID vendorId, boolean runningLow, UUID branchId, int limit, List<UUID> productIds,
            UUID excludeOrderId) {
        UUID tenantId = requireTenantId();
        List<StockInTemplateRow> rows;
        if (productIds != null && !productIds.isEmpty()) {
            rows = stockInTemplateService.rows(
                    productIds.stream().limit(StockInTemplateService.MAX_PICKER_PRODUCTS).toList(), null, null, null);
            runningLow = false;
        } else if (runningLow) {
            // "From John before": what is linked to John (received from him) and what was asked of him.
            rows = vendorId == null
                    ? List.of()
                    : withOrderedBefore(tenantId, vendorId,
                            stockInTemplateService.rows(List.of(), StockInTemplateFilter.BY_VENDOR, vendorId, null),
                            StockInTemplateService.MAX_PICKER_PRODUCTS);
        } else if ((search == null || search.isBlank()) && vendorId != null) {
            rows = withOrderedBefore(tenantId, vendorId, stockInTemplateService.pickerRows(null, vendorId, limit), limit);
        } else {
            rows = stockInTemplateService.pickerRows(search, vendorId, limit);
        }
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> rowProductIds = rows.stream().map(StockInTemplateRow::productId).distinct().toList();

        Map<UUID, Product> products = new HashMap<>();
        for (Product product : productRepository.findAllById(rowProductIds)) {
            products.put(product.getId(), product);
        }
        Map<UUID, int[]> stock = stockOf(tenantId, products, branchId);
        Map<UUID, BigDecimal> coming = comingInStockUnits(tenantId, rows, rowProductIds, excludeOrderId);
        Map<UUID, Object[]> last = lastOrdered(tenantId, vendorId, rowProductIds);
        LastPrices prices = lastPrices(tenantId, vendorId, rowProductIds);

        // Which line of each product is "usual": the unit last ordered, else its first pack, else its first.
        Map<UUID, String> usualUnit = new HashMap<>();
        for (StockInTemplateRow row : rows) {
            Object[] previous = last.get(row.productId());
            if (previous != null && UnitOptions.key(row.option()).equals(previous[1])) {
                usualUnit.put(row.productId(), (String) previous[1]);
            }
        }
        for (StockInTemplateRow row : rows) {
            if (row.option().isPack()) {
                usualUnit.putIfAbsent(row.productId(), UnitOptions.key(row.option()));
            }
        }
        for (StockInTemplateRow row : rows) {
            usualUnit.putIfAbsent(row.productId(), UnitOptions.key(row.option()));
        }

        Set<UUID> keep = null;
        if (runningLow) {
            keep = new LinkedHashSet<>();
            for (UUID productId : rowProductIds) {
                int[] figure = stock.get(productId);
                if (figure != null && isLowOrOut(figure)) {
                    keep.add(productId);
                }
                if (keep.size() >= RUNNING_LOW_LIMIT) {
                    break;
                }
            }
        }

        List<Line> lines = new ArrayList<>();
        for (StockInTemplateRow row : rows) {
            if (keep != null && !keep.contains(row.productId())) {
                continue;
            }
            BigDecimal factor = row.option().factorToStockUnit();
            String unit = UnitOptions.key(row.option());
            int[] figure = stock.get(row.productId());
            Object[] previous = last.get(row.productId());
            BigDecimal comingHere = coming.get(row.productId());
            LastPrice price = prices.of(row.productId(), unit, factor);
            lines.add(new Line(
                    row.productId(),
                    row.productName(),
                    row.sku(),
                    unit,
                    SheetUnitOptions.comesInLabel(row.option()),
                    row.option().isPack(),
                    figure == null ? null : inUnit(BigDecimal.valueOf(figure[0]), factor),
                    figure == null ? null : BigDecimal.valueOf(figure[0]),
                    factor == null ? BigDecimal.ONE : plain(factor),
                    figure == null ? null : state(figure),
                    comingHere == null || comingHere.signum() <= 0 ? null : inUnit(comingHere, factor),
                    previous != null && unit.equals(previous[1]) ? plain((BigDecimal) previous[2]) : null,
                    unit.equals(usualUnit.get(row.productId())),
                    price == null ? null : plain(price.amount()),
                    price == null ? null : price.on(),
                    price == null ? null : price.kind()));
        }
        return lines;
    }

    /** The suppliers ordered from most recently, newest first, each with up to three of what was asked for. */
    @Transactional(readOnly = true)
    public List<RecentSupplier> recentSuppliers() {
        UUID tenantId = requireTenantId();
        Map<UUID, RecentSupplier> byVendor = new LinkedHashMap<>();
        for (ExpectedDelivery order : expectedDeliveryRepository.findTop200ByClientIdAndVendorIsNotNullOrderByCreatedAtDesc(tenantId)) {
            UUID vendorId = order.getVendor().getId();
            if (byVendor.containsKey(vendorId)) {
                continue;
            }
            List<String> names = order.getLines().stream()
                    .map(ExpectedDeliveryLine::getProduct)
                    .map(Product::getName)
                    .distinct()
                    .limit(3)
                    .toList();
            byVendor.put(vendorId, new RecentSupplier(vendorId, order.getCreatedAt(), names));
        }
        return new ArrayList<>(byVendor.values());
    }

    /** {on hand, alert level or -1} in stock units, per product, at the branch (or the company). */
    private Map<UUID, int[]> stockOf(UUID tenantId, Map<UUID, Product> products, UUID branchId) {
        Map<UUID, int[]> stock = new HashMap<>();
        Map<UUID, Integer> thresholds = new HashMap<>();
        for (Product product : products.values()) {
            thresholds.put(product.getId(), product.getLowStockThreshold());
            stock.put(product.getId(), new int[] {
                    product.getQuantityOnHand(),
                    product.getLowStockThreshold() == null ? -1 : product.getLowStockThreshold()});
        }
        if (branchId != null && branchStockService.isMultiBranch(tenantId)) {
            Map<UUID, List<BranchFigure>> figures =
                    branchStockService.figures(tenantId, products.keySet(), List.of(branchId), thresholds);
            if (figures != null) {
                for (Map.Entry<UUID, List<BranchFigure>> entry : figures.entrySet()) {
                    if (entry.getValue().isEmpty()) {
                        continue;
                    }
                    BranchFigure figure = entry.getValue().getFirst();
                    stock.put(entry.getKey(), new int[] {
                            figure.quantityOnHand(),
                            figure.lowStockThreshold() == null ? -1 : figure.lowStockThreshold()});
                }
            }
        }
        return stock;
    }

    /**
     * What is still owed on open orders, per product, in its stock unit. Converted here with every way
     * the product is bought - its supplier's packs included, which the product alone doesn't know -
     * so 3 of Tony's 50 kg bags are 150 kg, not 3. A unit nothing here recognises is left as counted.
     */
    private Map<UUID, BigDecimal> comingInStockUnits(
            UUID tenantId, List<StockInTemplateRow> rows, List<UUID> productIds, UUID excludeOrderId) {
        Map<UUID, Map<String, BigDecimal>> factors = new HashMap<>();
        for (StockInTemplateRow row : rows) {
            factors.computeIfAbsent(row.productId(), key -> new HashMap<>())
                    .putIfAbsent(UnitOptions.key(row.option()), row.option().factorToStockUnit());
        }
        Map<UUID, BigDecimal> coming = new HashMap<>();
        List<Object[]> owed = excludeOrderId == null
                ? expectedDeliveryRepository.outstandingByProductAndUnit(tenantId, productIds)
                : expectedDeliveryRepository.outstandingByProductAndUnitExcept(tenantId, productIds, excludeOrderId);
        for (Object[] row : owed) {
            UUID productId = (UUID) row[0];
            BigDecimal quantity = (BigDecimal) row[2];
            if (quantity == null) {
                continue;
            }
            BigDecimal factor = factors.getOrDefault(productId, Map.of()).getOrDefault((String) row[1], BigDecimal.ONE);
            coming.merge(productId, quantity.multiply(factor), BigDecimal::add);
        }
        return coming;
    }

    /**
     * The supplier's linked products, then what was asked of them before and isn't linked yet (most
     * recent first), up to {@code limit} products. Called-off orders don't count. This is only ever a
     * suggestion on a new order - nothing here says anything was bought.
     */
    private List<StockInTemplateRow> withOrderedBefore(
            UUID tenantId, UUID vendorId, List<StockInTemplateRow> linked, int limit) {
        Set<UUID> have = new LinkedHashSet<>();
        for (StockInTemplateRow row : linked) {
            have.add(row.productId());
        }
        List<UUID> extra = new ArrayList<>();
        for (Object[] row : expectedDeliveryRepository.productsOrderedFrom(
                tenantId, vendorId, org.springframework.data.domain.PageRequest.of(0, StockInTemplateService.MAX_PICKER_PRODUCTS))) {
            UUID productId = (UUID) row[0];
            if (!have.contains(productId)) {
                extra.add(productId);
            }
        }
        if (extra.isEmpty()) {
            return linked;
        }
        List<StockInTemplateRow> rows = new ArrayList<>(linked);
        rows.addAll(stockInTemplateService.rows(extra, null, null, null));
        // At most `limit` products in all: the linked ones first, then the most recently ordered.
        Set<UUID> kept = new LinkedHashSet<>();
        List<StockInTemplateRow> bounded = new ArrayList<>();
        for (StockInTemplateRow row : rows) {
            if (kept.contains(row.productId()) || kept.size() < limit) {
                kept.add(row.productId());
                bounded.add(row);
            }
        }
        return bounded;
    }

    private record LastPrice(BigDecimal amount, OffsetDateTime at, String kind) {

        java.time.LocalDate on() {
            return at.atZoneSameInstant(java.time.ZoneId.of("Africa/Lagos")).toLocalDate();
        }
    }

    /**
     * The newest price per product from this supplier: the last delivery recorded with a price
     * (PAID), or the last purchase order that agreed one (ORDERED), whichever is newer.
     */
    private record LastPrices(Map<UUID, Object[]> paid, Map<UUID, Map<String, Object[]>> ordered) {

        LastPrice of(UUID productId, String unit, BigDecimal factor) {
            LastPrice fromDelivery = null;
            Object[] delivery = paid.get(productId);
            if (delivery != null) {
                // A delivery is kept per stock unit; one of this unit is that times its factor. When
                // it was typed in this very unit the typed figure is the exact one (no kobo drift).
                BigDecimal perStockUnit = (BigDecimal) delivery[1];
                BigDecimal typed = (BigDecimal) delivery[2];
                BigDecimal worked = perStockUnit.multiply(factor == null ? BigDecimal.ONE : factor)
                        .setScale(2, RoundingMode.HALF_UP);
                BigDecimal amount = typed != null && typed.subtract(worked).abs().compareTo(BigDecimal.ONE) < 0
                        ? typed : worked;
                fromDelivery = new LastPrice(amount, (OffsetDateTime) delivery[3], "PAID");
            }
            LastPrice fromOrder = null;
            Object[] order = ordered.getOrDefault(productId, Map.of()).get(unit);
            if (order != null) {
                fromOrder = new LastPrice((BigDecimal) order[2], (OffsetDateTime) order[3], "ORDERED");
            }
            if (fromDelivery == null) {
                return fromOrder;
            }
            if (fromOrder == null) {
                return fromDelivery;
            }
            return fromOrder.at().isAfter(fromDelivery.at()) ? fromOrder : fromDelivery;
        }
    }

    private LastPrices lastPrices(UUID tenantId, UUID vendorId, List<UUID> productIds) {
        Map<UUID, Object[]> paid = new HashMap<>();
        Map<UUID, Map<String, Object[]>> ordered = new HashMap<>();
        if (vendorId == null || productIds.isEmpty()) {
            return new LastPrices(paid, ordered);
        }
        org.springframework.data.domain.Pageable enough = org.springframework.data.domain.PageRequest.of(0, 500);
        for (Object[] row : stockMovementRepository.pricesPaidTo(tenantId, vendorId, productIds, enough)) {
            paid.putIfAbsent((UUID) row[0], row);
        }
        for (Object[] row : expectedDeliveryRepository.pricesOrderedFrom(tenantId, vendorId, productIds, enough)) {
            ordered.computeIfAbsent((UUID) row[0], key -> new HashMap<>()).putIfAbsent((String) row[1], row);
        }
        return new LastPrices(paid, ordered);
    }

    /** The newest {productId, unit, quantity} ordered from this supplier, per product. */
    private Map<UUID, Object[]> lastOrdered(UUID tenantId, UUID vendorId, List<UUID> productIds) {
        Map<UUID, Object[]> last = new HashMap<>();
        if (vendorId == null || productIds.isEmpty()) {
            return last;
        }
        for (Object[] row : expectedDeliveryRepository.lastOrderedFrom(tenantId, vendorId, productIds)) {
            last.putIfAbsent((UUID) row[0], row);
        }
        return last;
    }

    private static boolean isLowOrOut(int[] figure) {
        StockStatus state = state(figure);
        return state == StockStatus.LOW || state == StockStatus.OUT;
    }

    private static StockStatus state(int[] figure) {
        return StockStatus.of(figure[0], figure[1] < 0 ? null : figure[1]);
    }

    /** A stock-unit quantity said in this unit, rounded down to one decimal: 170 kg is "3.4" bags. */
    private static BigDecimal inUnit(BigDecimal stockUnits, BigDecimal factor) {
        if (factor == null || factor.signum() <= 0) {
            return plain(stockUnits);
        }
        return plain(stockUnits.divide(factor, 1, RoundingMode.DOWN));
    }

    private static BigDecimal plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    private static UUID requireTenantId() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context set");
        }
        return tenantId;
    }
}
