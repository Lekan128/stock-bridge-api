package com.procurepal_services.stock_bridge_api.expected;

import com.procurepal_services.stock_bridge_api.entity.CompanyVendor;
import com.procurepal_services.stock_bridge_api.entity.ExpectedDelivery;
import com.procurepal_services.stock_bridge_api.entity.ExpectedDeliveryLine;
import com.procurepal_services.stock_bridge_api.entity.ExpectedDeliveryStatus;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.expected.dto.ExpectedDeliveryRequest;
import com.procurepal_services.stock_bridge_api.expected.dto.ExpectedDeliveryResponse;
import com.procurepal_services.stock_bridge_api.product.bulk.SheetUnitOptions;
import com.procurepal_services.stock_bridge_api.product.bulk.StockInTemplateRow;
import com.procurepal_services.stock_bridge_api.product.bulk.StockInTemplateService;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOptions;
import com.procurepal_services.stock_bridge_api.repository.CompanyVendorRepository;
import com.procurepal_services.stock_bridge_api.repository.ExpectedDeliveryRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a company is waiting for from an off-platform supplier (BULK_IMPORT_CX_PLAN.md task 3.1).
 *
 * <p>Two jobs only: answer "what have we got coming?", and hand the delivery screen a filled-in
 * form when it arrives. Receiving itself is not here - it goes through the ordinary stock-in
 * import, and this class is only told afterwards, by {@link #credit} and {@link #uncredit}. One
 * stock path, one ledger.
 */
@Service
@RequiredArgsConstructor
public class ExpectedDeliveryService {

    private static final DateTimeFormatter DUE_FORMAT =
            DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private final ExpectedDeliveryRepository expectedDeliveryRepository;
    private final CompanyVendorRepository companyVendorRepository;
    private final ProductRepository productRepository;
    private final StockInTemplateService stockInTemplateService;

    @Transactional
    public ExpectedDeliveryResponse create(ExpectedDeliveryRequest request, UUID actingUserId) {
        UUID tenantId = requireTenantId();
        CompanyVendor vendor = request.vendorId() == null
                ? null
                : companyVendorRepository.findByIdAndClientIdAndActiveTrue(request.vendorId(), tenantId)
                        .orElseThrow(ExpectedDeliveryException::noSuchSupplier);

        // The ways each of these products is actually bought, read through the tenant's own
        // catalog. This is the same call the delivery picker makes, so an expectation can only
        // ever be recorded in a unit a delivery could later be received in.
        Map<UUID, Map<String, StockInTemplateRow>> waysToBuy = waysToBuy(
                request.lines().stream().map(ExpectedDeliveryRequest.Line::productId).distinct().toList());

        ExpectedDelivery expected = ExpectedDelivery.builder()
                .vendor(vendor)
                .expectedDate(parseDate(request.expectedDate()))
                .reference(blankToNull(request.reference()))
                .note(blankToNull(request.note()))
                .status(ExpectedDeliveryStatus.OPEN)
                .createdBy(actingUserId)
                .build();

        for (ExpectedDeliveryRequest.Line line : request.lines()) {
            Map<String, StockInTemplateRow> byUnit = waysToBuy.get(line.productId());
            if (byUnit == null || byUnit.isEmpty()) {
                throw ExpectedDeliveryException.noSuchProduct();
            }
            StockInTemplateRow row = byUnit.get(line.unit());
            if (row == null) {
                throw ExpectedDeliveryException.unknownUnit(
                        byUnit.values().iterator().next().productName(),
                        UnitOptions.spokenPhraseOfSubmitted(line.unit()));
            }
            Product product = productRepository.getReferenceById(line.productId());
            expected.addLine(ExpectedDeliveryLine.builder()
                    .product(product)
                    .unit(line.unit())
                    .quantity(line.quantity())
                    .price(line.price())
                    .receivedQuantity(BigDecimal.ZERO)
                    .build());
        }

        return respond(expectedDeliveryRepository.saveAndFlush(expected));
    }

    @Transactional(readOnly = true)
    public Page<ExpectedDeliveryResponse> list(ExpectedDeliveryStatus status, Pageable pageable) {
        UUID tenantId = requireTenantId();
        Page<ExpectedDelivery> page = status == null
                ? expectedDeliveryRepository.findAllByClientIdOrderByCreatedAtDesc(tenantId, pageable)
                : expectedDeliveryRepository.findAllByClientIdAndStatusOrderByCreatedAtDesc(tenantId, status, pageable);
        // One lookup for every product on the page rather than one per line.
        Map<UUID, Map<String, StockInTemplateRow>> waysToBuy = waysToBuy(page.getContent().stream()
                .flatMap(expected -> expected.getLines().stream())
                .map(line -> line.getProduct().getId())
                .distinct()
                .toList());
        return page.map(expected -> respond(expected, waysToBuy));
    }

    @Transactional(readOnly = true)
    public ExpectedDeliveryResponse get(UUID id) {
        return respond(require(id));
    }

    @Transactional
    public ExpectedDeliveryResponse cancel(UUID id) {
        ExpectedDelivery expected = require(id);
        if (expected.getStatus() == ExpectedDeliveryStatus.RECEIVED) {
            throw ExpectedDeliveryException.alreadyClosed("received");
        }
        expected.setStatus(ExpectedDeliveryStatus.CANCELLED);
        return respond(expectedDeliveryRepository.saveAndFlush(expected));
    }

    /**
     * How much of each product is still expected, <b>in that product's own stock unit</b>, for the
     * product list's "and N coming".
     *
     * <p>Kept separate from {@code Product.incoming_quantity} deliberately - see
     * {@link ExpectedDelivery}'s own note on why a promise and a paid marketplace order must not be
     * added together.
     *
     * <h2>Why this converts instead of summing</h2>
     * Each line is counted in the unit it was ordered in. Ten 50 kg bags and five loose kg are
     * "10" and "5", and 15 is not a quantity of anything - least of all one to print beside
     * "1,000 kg on hand". So every line is taken to the product's stock unit first, which is the
     * only basis on which two of them can be added, and is the number the figure beside it is in.
     */
    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> outstandingByProduct(UUID tenantId, List<UUID> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = expectedDeliveryRepository.outstandingByProductAndUnit(tenantId, productIds);
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Product> products = new HashMap<>();
        for (Product product : productRepository.findAllById(
                rows.stream().map(row -> (UUID) row[0]).distinct().toList())) {
            products.put(product.getId(), product);
        }

        Map<UUID, BigDecimal> outstanding = new HashMap<>();
        for (Object[] row : rows) {
            UUID productId = (UUID) row[0];
            String unit = (String) row[1];
            BigDecimal quantity = (BigDecimal) row[2];
            Product product = products.get(productId);
            if (product == null || quantity == null) {
                continue;
            }
            BigDecimal inStockUnits = UnitOptions.resolveKey(UnitOptions.forProduct(product), unit)
                    .map(option -> quantity.multiply(option.factorToStockUnit()))
                    // The pack has been changed or removed since the order was placed. The number
                    // is then in a unit nothing can convert, so it is left as counted rather than
                    // silently multiplied by a factor that no longer applies.
                    .orElse(quantity);
            outstanding.merge(productId, inStockUnits, BigDecimal::add);
        }
        outstanding.replaceAll((productId, total) -> total.stripTrailingZeros());
        return outstanding;
    }

    /**
     * Credits what a committed stock-in actually received, line by line, and closes the record once
     * every line is met. Matched on {product, unit} - the same pair the expectation was written in
     * and the delivery screen sent back - so a receipt of something that was not expected simply
     * finds no line and is left alone rather than being forced onto the nearest one.
     *
     * @param received how much arrived, keyed by {productId, unit}, counted in that unit.
     */
    @Transactional
    public void credit(UUID expectedDeliveryId, UUID tenantId, Map<ReceiptKey, BigDecimal> received) {
        apply(expectedDeliveryId, tenantId, received, false);
    }

    /** The same, reversed: an undone import un-receives exactly what it credited. */
    @Transactional
    public void uncredit(UUID expectedDeliveryId, UUID tenantId, Map<ReceiptKey, BigDecimal> received) {
        apply(expectedDeliveryId, tenantId, received, true);
    }

    private void apply(
            UUID expectedDeliveryId, UUID tenantId, Map<ReceiptKey, BigDecimal> received, boolean reverse) {
        if (expectedDeliveryId == null || received == null || received.isEmpty()) {
            return;
        }
        Optional<ExpectedDelivery> found =
                expectedDeliveryRepository.findByIdAndClientId(expectedDeliveryId, tenantId);
        if (found.isEmpty()) {
            return;
        }
        ExpectedDelivery expected = found.get();
        if (expected.getStatus() == ExpectedDeliveryStatus.CANCELLED) {
            // Cancelled after the goods were already on their way. The stock is real and stays;
            // there is simply no expectation left to credit.
            return;
        }
        for (ExpectedDeliveryLine line : expected.getLines()) {
            BigDecimal amount = received.get(new ReceiptKey(line.getProduct().getId(), line.getUnit()));
            if (amount != null) {
                line.credit(reverse ? amount.negate() : amount);
            }
        }
        expected.setStatus(
                expected.isFullyReceived() ? ExpectedDeliveryStatus.RECEIVED : ExpectedDeliveryStatus.OPEN);
        expectedDeliveryRepository.saveAndFlush(expected);
    }

    /** What a receipt is matched on: the product, and the unit it was counted in. */
    public record ReceiptKey(UUID productId, String unit) {
    }

    /** The company's expectation with this id, for the receive flow as well as the screens. */
    @Transactional(readOnly = true)
    public ExpectedDelivery require(UUID id) {
        return expectedDeliveryRepository.findByIdAndClientId(id, requireTenantId())
                .orElseThrow(ExpectedDeliveryException::notFound);
    }

    // ------------------------------------------------------------------------------ responses ----

    private ExpectedDeliveryResponse respond(ExpectedDelivery expected) {
        return respond(expected, waysToBuy(expected.getLines().stream()
                .map(line -> line.getProduct().getId())
                .distinct()
                .toList()));
    }

    private ExpectedDeliveryResponse respond(
            ExpectedDelivery expected, Map<UUID, Map<String, StockInTemplateRow>> waysToBuy) {
        List<ExpectedDeliveryResponse.Line> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int outstandingLines = 0;
        for (ExpectedDeliveryLine line : expected.getLines()) {
            UUID productId = line.getProduct().getId();
            StockInTemplateRow row = Optional.ofNullable(waysToBuy.get(productId))
                    .map(byUnit -> byUnit.get(line.getUnit()))
                    .orElse(null);
            BigDecimal outstanding = line.outstanding();
            if (outstanding.signum() > 0) {
                outstandingLines++;
            }
            if (line.getPrice() != null) {
                total = total.add(line.getPrice().multiply(line.getQuantity()));
            }
            lines.add(new ExpectedDeliveryResponse.Line(
                    line.getId(),
                    productId,
                    // The product may have been renamed, or its packs changed, since this was
                    // written down. The catalog's word is the current one; the stored unit key is
                    // still what gets sent back when receiving.
                    row != null ? row.productName() : line.getProduct().getName(),
                    row != null ? row.sku() : line.getProduct().getSku(),
                    line.getUnit(),
                    row != null
                            ? SheetUnitOptions.comesInLabel(row.option())
                            : UnitOptions.spokenPhraseOfSubmitted(line.getUnit()),
                    line.getQuantity(),
                    line.getReceivedQuantity(),
                    outstanding,
                    line.getPrice()));
        }
        String vendorName = expected.getVendor() == null ? null : expected.getVendor().getName();
        return new ExpectedDeliveryResponse(
                expected.getId(),
                expected.getStatus(),
                title(vendorName, expected.getExpectedDate()),
                expected.getVendor() == null ? null : expected.getVendor().getId(),
                vendorName,
                expected.getExpectedDate(),
                expected.getReference(),
                expected.getNote(),
                lines,
                outstandingLines,
                total.signum() == 0 ? null : total,
                expected.getStatus() == ExpectedDeliveryStatus.OPEN && outstandingLines > 0,
                expected.getCreatedAt());
    }

    /** "Tony Stores, due 22 Sep" - or just the supplier, or just the date, or "Expected delivery". */
    static String title(String vendorName, LocalDate expectedDate) {
        String due = expectedDate == null ? null : "due " + expectedDate.format(DUE_FORMAT);
        if (vendorName == null) {
            return due == null ? "Expected delivery" : "Expected delivery, " + due;
        }
        return due == null ? vendorName : vendorName + ", " + due;
    }

    /** Every way each of these products is bought, keyed by product and then by unit key. */
    private Map<UUID, Map<String, StockInTemplateRow>> waysToBuy(List<UUID> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Map<String, StockInTemplateRow>> byProduct = new LinkedHashMap<>();
        for (StockInTemplateRow row : stockInTemplateService.rows(productIds, null, null, null)) {
            byProduct.computeIfAbsent(row.productId(), key -> new LinkedHashMap<>())
                    .putIfAbsent(UnitOptions.key(row.option()), row);
        }
        return byProduct;
    }

    private static LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw new ExpectedDeliveryException(HttpStatus.BAD_REQUEST, "We could not read that date.");
        }
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    private UUID requireTenantId() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context set");
        }
        return tenantId;
    }
}
