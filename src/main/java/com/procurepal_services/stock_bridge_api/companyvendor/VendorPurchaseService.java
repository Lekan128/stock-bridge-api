package com.procurepal_services.stock_bridge_api.companyvendor;

import com.procurepal_services.stock_bridge_api.companyvendor.dto.VendorProductPriceResponse;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.VendorSpendSummary;
import com.procurepal_services.stock_bridge_api.entity.CompanyVendor;
import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.OrderItem;
import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.entity.ProductVendor;
import com.procurepal_services.stock_bridge_api.repository.OrderItemRepository;
import com.procurepal_services.stock_bridge_api.repository.OrderRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductVendorRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "What have we bought from this supplier, and what did we last pay them for it" -
 * VENDOR_RESEARCH.md Section C item 13, which argues that answering this is the
 * whole reason a vendor record earns its place over a contacts app.
 *
 * <h2>These are ORDINARY IN-TENANT READS</h2>
 * Worth stating because the word "vendor" makes it look like a seller-side query
 * that would need {@code PlatformOwnerGuard.readAcrossTenants} or
 * {@code VendorGuard}. It is the opposite. {@code orders.client_id} is the BUYER,
 * and the buyer is the one asking; the supplier appears only as
 * {@code orders.seller_client_id} in the predicate. Every row read here already
 * belongs to the caller. If a query in this class ever seems to need the tenant
 * filter lifted, the predicate has been written backwards - it is asking "which
 * companies bought from this seller", which is a different question, is not this
 * company's business, and is the leak CompanyVendorRepository's javadoc rules out.
 *
 * <h2>EXTERNAL vendors have zero spend here, and that is the finished answer for THIS class</h2>
 * A supplier the company deals with entirely off-platform has no orders in this system and
 * never will. Both methods below return the empty/zero answer for them immediately, without a
 * query, because there is no seller id to filter on - {@code platform_client_id} is NULL for
 * EXTERNAL by CHECK constraint.
 *
 * <p>This is deliberately narrower than "this vendor has no purchase history" - a manual
 * stock-in records a real, priced delivery against ANY company vendor, EXTERNAL included (see
 * {@code StockMovement.companyVendor}), and {@code PurchaseHistoryService} surfaces those
 * alongside orders on the purchase-history screen. What stays order-only here is spend-to-date
 * and last purchase price: a manual entry is not run through the same checkout/payment path an
 * order is, so folding it into "spend" would blend two different kinds of fact. If that is ever
 * wanted, it is a deliberate widening of THIS class, not a bug in it.
 *
 * <h2>What counts as a purchase</h2>
 * One rule, applied everywhere here: the order reached PLACED
 * ({@code placed_at IS NOT NULL}). Cancelled orders are then excluded from the
 * MONEY (spend, last price) but kept in the purchase-history LIST, badged with their status -
 * see {@code PurchaseHistoryService} and {@code VendorSpendSummary} for the reasoning behind
 * each half.
 */
@Service
@RequiredArgsConstructor
public class VendorPurchaseService {

    /**
     * The one status excluded from every money figure. Named rather than inlined
     * so the three call sites cannot drift, and so "cancelled purchases do not
     * count" is one decision rather than three coincidences.
     */
    private static final OrderStatus NOT_A_PURCHASE = OrderStatus.CANCELLED;

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductVendorRepository productVendorRepository;

    /** Order count, lifetime spend and last purchase date. Zeroes for EXTERNAL. */
    @Transactional(readOnly = true)
    public VendorSpendSummary spendSummary(CompanyVendor vendor) {
        UUID sellerClientId = vendor.getPlatformClientId();
        if (sellerClientId == null) {
            return VendorSpendSummary.none();
        }
        UUID buyerClientId = vendor.getClientId();
        return new VendorSpendSummary(
                orderRepository.countByClientIdAndSellerClientIdAndPlacedAtIsNotNullAndStatusNot(
                        buyerClientId, sellerClientId, NOT_A_PURCHASE),
                orderRepository.sumTotalByClientIdAndSellerClientId(buyerClientId, sellerClientId, NOT_A_PURCHASE),
                orderRepository.findLastPurchasedAt(buyerClientId, sellerClientId, NOT_A_PURCHASE));
    }

    /**
     * What this company buys from this supplier, with the last price paid for each.
     *
     * <h2>The product set comes from the LINK, the price comes from the ORDERS</h2>
     * Two different sources answering two different questions, and merging them
     * would break both. A {@code product_vendors} row (see {@link ProductVendor}, V19 - this
     * used to be the single {@code products.company_vendor_id} FK) says "this is a supplier we
     * buy this item from" - it is a standing arrangement, and it is the only thing an EXTERNAL
     * supplier can have. The order lines say what was actually paid and
     * when, which is a fact nobody typed in. A product linked but never bought
     * shows with a null price (see VendorProductPriceResponse); a product bought
     * but linked elsewhere shows under the vendor it is linked to, and its real
     * purchase still appears in this vendor's history.
     */
    @Transactional(readOnly = true)
    public List<VendorProductPriceResponse> suppliedProducts(CompanyVendor vendor) {
        UUID buyerClientId = vendor.getClientId();
        List<Product> products = productVendorRepository
                .findAllByClientIdAndCompanyVendorIdAndProductActive(buyerClientId, vendor.getId())
                .stream()
                .map(ProductVendor::getProduct)
                .toList();
        if (products.isEmpty()) {
            return List.of();
        }

        Map<UUID, LastPurchase> lastPurchases = lastPurchaseByProduct(
                buyerClientId,
                vendor.getPlatformClientId(),
                products.stream().map(Product::getId).toList());

        return products.stream()
                .map(product -> {
                    LastPurchase last = lastPurchases.get(product.getId());
                    if (last == null) {
                        return VendorProductPriceResponse.withoutPurchase(product);
                    }
                    return new VendorProductPriceResponse(
                            product.getId(),
                            product.getName(),
                            product.getSku(),
                            product.getUnitOfMeasure(),
                            product.getImageUrl(),
                            product.getQuantityOnHand(),
                            product.getIncomingQuantity(),
                            last.item().getUnitPrice(),
                            last.item().getQuantity(),
                            last.order().getPlacedAt(),
                            last.order().getId(),
                            last.order().getOrderNumber());
                })
                .toList();
    }

    /**
     * The most recent qualifying line per buyer product, resolved in one query.
     *
     * <p>Read newest-first and kept on first sight rather than compared: the
     * repository already orders by {@code placed_at DESC}, so the first line seen
     * for a product IS the last one bought. A SQL-level "greatest per group" would
     * be a window function or a correlated subquery for a set that is at most this
     * one vendor's product list, which is not a trade worth making here.
     */
    private Map<UUID, LastPurchase> lastPurchaseByProduct(
            UUID buyerClientId, UUID sellerClientId, List<UUID> buyerProductIds) {
        if (sellerClientId == null || buyerProductIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, LastPurchase> byProduct = new HashMap<>();
        for (OrderItem item : orderItemRepository.findPurchaseLinesForBuyerProducts(
                buyerClientId, sellerClientId, buyerProductIds, NOT_A_PURCHASE)) {
            byProduct.computeIfAbsent(item.getBuyerProductId(), key -> new LastPurchase(item, item.getOrder()));
        }
        return byProduct;
    }

    /** The line and the order it came from, so the response can name both. */
    private record LastPurchase(OrderItem item, Order order) {
    }
}
