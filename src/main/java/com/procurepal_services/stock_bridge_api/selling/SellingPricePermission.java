package com.procurepal_services.stock_bridge_api.selling;

import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.stock.StockWritePermissionGuard;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Who may change a selling price (V52, MULTI_BRANCH_UX_PLAN.md section 5.14). One product carries one
 * price for every branch, so a branch storekeeper who can edit products could otherwise change the
 * price at every other branch without knowing it. {@code CHANGE_SELLING_PRICES} splits that out of
 * {@code MANAGE_PRODUCTS}; every role that had the one was given the other, so nobody lost anything.
 *
 * <p>It governs the product's usual price only. What a sale line charges is the seller's call at the
 * counter, as before, and a sale below the usual price is flagged, not refused.
 *
 * <p>Read fresh from the database, like stock writes, so a permission taken away counts at once.
 */
@Component
@RequiredArgsConstructor
public class SellingPricePermission {

    public static final String AUTHORITY = "CHANGE_SELLING_PRICES";

    private final StockWritePermissionGuard permissions;

    /** Whether {@code userId} may set a selling price. A write the system makes (no person) may. */
    public boolean allows(UUID userId) {
        return userId == null || permissions.has(userId, AUTHORITY);
    }

    public void require(UUID userId) {
        if (!allows(userId)) {
            throw new SellingPriceNotPermittedException();
        }
    }

    /**
     * Whether a price sent with a product edit is the one it already has. The product form sends the
     * current price back with every save, so only a different one is a change of price.
     */
    public static boolean isCurrent(Product product, BigDecimal price, String unit) {
        if (unit != null && !unit.isBlank() && product.getUnitPriceEnteredUnit() != null) {
            return unit.equalsIgnoreCase(product.getUnitPriceEnteredUnit())
                    && sameAmount(price, product.getUnitPriceEntered());
        }
        return sameAmount(price, product.getUnitPrice());
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return Objects.equals(a, b);
        }
        return a.compareTo(b) == 0;
    }
}
