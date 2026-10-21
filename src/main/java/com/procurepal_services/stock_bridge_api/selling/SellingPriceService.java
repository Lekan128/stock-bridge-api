package com.procurepal_services.stock_bridge_api.selling;

import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.entity.ProductPriceChange;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.product.InvalidUnitOfMeasureException;
import com.procurepal_services.stock_bridge_api.product.ProductNotFoundException;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOption;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOptions;
import com.procurepal_services.stock_bridge_api.repository.ProductPriceChangeRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import com.procurepal_services.stock_bridge_api.selling.dto.PriceChangeResponse;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place a product's selling price is written (SELLING_PRICE_PLAN.md section 4.5): the
 * per-stock-unit {@code unitPrice} checkout multiplies, and the price exactly as typed with the
 * unit it was typed per - kept in step, and every change logged.
 *
 * <p>"5,000 per carton" of 24 pieces is stored as unitPrice 208.33 and kept as entered 5,000 per
 * CARTON; a sale in cartons pre-fills from the 5,000. A price typed per stock unit is just
 * unitPrice, with nothing entered beside it.
 */
@Service
@RequiredArgsConstructor
public class SellingPriceService {

    private final ProductPriceChangeRepository priceChangeRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;

    /** The three price fields as they stand - taken before a change, compared after. */
    public record Snapshot(BigDecimal unitPrice, BigDecimal entered, String enteredUnit) {

        public static Snapshot of(Product product) {
            return new Snapshot(product.getUnitPrice(), product.getUnitPriceEntered(), product.getUnitPriceEnteredUnit());
        }

        boolean sameAs(Snapshot other) {
            return equalMoney(unitPrice, other.unitPrice)
                    && equalMoney(entered, other.entered)
                    && Objects.equals(enteredUnit, other.enteredUnit);
        }

        private static boolean equalMoney(BigDecimal a, BigDecimal b) {
            return a == null ? b == null : b != null && a.compareTo(b) == 0;
        }
    }

    /**
     * Sets the selling price as typed. {@code unit} names one of the product's own units (its
     * stock unit, its pack) - null or blank is the stock unit. A null {@code price} clears it.
     * The product's units must already be set: the conversion is by its pack.
     *
     * @throws InvalidUnitOfMeasureException when {@code unit} is not one of this product's units.
     */
    public void apply(Product product, BigDecimal price, String unit) {
        if (price == null) {
            product.setUnitPrice(null);
            product.setUnitPriceEntered(null);
            product.setUnitPriceEnteredUnit(null);
            return;
        }
        if (unit == null || unit.isBlank()) {
            setPerStockUnit(product, price);
            return;
        }
        List<UnitOption> options = UnitOptions.forProduct(product);
        UnitOption option = UnitOptions.resolve(options, unit)
                .orElseThrow(() -> new InvalidUnitOfMeasureException(unit.trim(), "unit for this product's price"));
        if (option.isStockUnit()) {
            setPerStockUnit(product, price);
            return;
        }
        product.setUnitPrice(option.toStockUnitPrice(price).setScale(2, RoundingMode.HALF_UP));
        product.setUnitPriceEntered(price.setScale(2, RoundingMode.HALF_UP));
        product.setUnitPriceEnteredUnit(option.code());
    }

    /**
     * After a product's pack changes: the typed price is the owner's truth ("5,000 a carton"), so
     * the per-stock-unit price is worked out again from it at the new size. If the unit it was
     * typed per is no longer one of the product's units, the per-stock-unit price stands and the
     * typed one is dropped - there is no carton left for it to be per.
     */
    public void reconcile(Product product) {
        if (product.getUnitPriceEntered() == null || product.getUnitPriceEnteredUnit() == null) {
            return;
        }
        Optional<UnitOption> option = UnitOptions.resolve(UnitOptions.forProduct(product), product.getUnitPriceEnteredUnit());
        if (option.isPresent() && !option.get().isStockUnit()) {
            product.setUnitPrice(option.get().toStockUnitPrice(product.getUnitPriceEntered()).setScale(2, RoundingMode.HALF_UP));
        } else {
            product.setUnitPriceEntered(null);
            product.setUnitPriceEnteredUnit(null);
        }
    }

    /** Writes a {@link ProductPriceChange} if the price differs from {@code before}; nothing otherwise. */
    public void recordIfChanged(Product product, Snapshot before, ProductPriceChange.Source source, UUID changedBy) {
        Snapshot after = Snapshot.of(product);
        if (before.sameAs(after) || product.getId() == null) {
            return;
        }
        priceChangeRepository.save(ProductPriceChange.builder()
                .clientId(product.getClientId())
                .productId(product.getId())
                .oldUnitPrice(before.unitPrice())
                .newUnitPrice(after.unitPrice())
                .oldEnteredPrice(before.entered())
                .oldEnteredUnit(before.enteredUnit())
                .newEnteredPrice(after.entered())
                .newEnteredUnit(after.enteredUnit())
                .source(source)
                .changedBy(changedBy)
                .changedAt(OffsetDateTime.now())
                .build());
    }

    /** The product's price changes, newest first; 404 (ProductNotFoundException) for another company's product. */
    @Transactional(readOnly = true)
    public List<PriceChangeResponse> history(UUID productId) {
        UUID tenantId = TenantContext.get();
        if (tenantId == null || productRepository.findByIdAndClientId(productId, tenantId).isEmpty()) {
            throw new ProductNotFoundException();
        }
        return priceChangeRepository.findAllByClientIdAndProductIdOrderByChangedAtDesc(tenantId, productId).stream()
                .map(change -> new PriceChangeResponse(
                        change.getOldUnitPrice(),
                        change.getNewUnitPrice(),
                        change.getOldEnteredPrice(),
                        change.getOldEnteredUnit(),
                        change.getNewEnteredPrice(),
                        change.getNewEnteredUnit(),
                        change.getSource(),
                        change.getChangedBy() == null ? null : userRepository.findById(change.getChangedBy()).map(SellingPriceService::nameOf).orElse(null),
                        change.getChangedAt()))
                .toList();
    }

    private static String nameOf(User user) {
        String first = user.getFirstName() == null ? "" : user.getFirstName().trim();
        String last = user.getLastName() == null ? "" : user.getLastName().trim();
        String full = (first + " " + last).trim();
        return full.isEmpty() ? user.getUsername() : full;
    }

    private static void setPerStockUnit(Product product, BigDecimal price) {
        product.setUnitPrice(price);
        product.setUnitPriceEntered(null);
        product.setUnitPriceEnteredUnit(null);
    }
}
