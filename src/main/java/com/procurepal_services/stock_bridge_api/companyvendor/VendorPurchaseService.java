package com.procurepal_services.stock_bridge_api.companyvendor;

import com.procurepal_services.stock_bridge_api.companyvendor.dto.VendorProductPriceResponse;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.VendorPurchaseResponse;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.VendorSpendSummary;
import com.procurepal_services.stock_bridge_api.entity.CompanyVendor;
import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.OrderItem;
import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.repository.OrderItemRepository;
import com.procurepal_services.stock_bridge_api.repository.OrderRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
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
 * <h2>EXTERNAL vendors have no history, and that is the finished answer</h2>
 * A supplier the company deals with entirely off-platform has no orders in this
 * system and never will. Every method here returns the empty/zero answer for them
 * immediately, without a query, because there is no seller id to filter on -
 * {@code platform_client_id} is NULL for EXTERNAL by CHECK constraint. The screens
 * render a purpose-written empty state saying why rather than an empty table.
 *
 * <p>Recording off-platform purchases by hand would give those rows a history, and
 * is deliberately NOT built: it is a second, unverified source of truth about money
 * next to a verified one, and it needs its own decisions about who may enter a
 * price and whether it moves stock. Out of scope by explicit instruction.
 *
 * <h2>What counts as a purchase</h2>
 * One rule, applied everywhere here: the order reached PLACED
 * ({@code placed_at IS NOT NULL}). Cancelled orders are then excluded from the
 * MONEY (spend, last price) but kept in the history LIST, badged with their status.
 * See VendorSpendSummary and VendorPurchaseResponse for the reasoning behind each
 * half.
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
    private final ProductRepository productRepository;

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
     * would break both. {@code products.company_vendor_id} says "this is who we get
     * this item from" - it is a standing arrangement, and it is the only thing an
     * EXTERNAL supplier can have. The order lines say what was actually paid and
     * when, which is a fact nobody typed in. A product linked but never bought
     * shows with a null price (see VendorProductPriceResponse); a product bought
     * but linked elsewhere shows under the vendor it is linked to, and its real
     * purchase still appears in this vendor's history.
     */
    @Transactional(readOnly = true)
    public List<VendorProductPriceResponse> suppliedProducts(CompanyVendor vendor) {
        UUID buyerClientId = vendor.getClientId();
        List<Product> products = productRepository.findAllByClientIdAndCompanyVendorIdAndActiveTrueOrderByNameAsc(
                buyerClientId, vendor.getId());
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
     * The purchase-history screen: this company's orders from this supplier,
     * newest first, with their lines.
     *
     * <p>One query for the page of orders and one for all their lines together,
     * rather than a lines query per order. With open-in-view on, the lazy
     * {@code order.items} route would be an N+1 that only shows up under a buyer
     * with real history - which is the buyer it matters for.
     */
    @Transactional(readOnly = true)
    public Page<VendorPurchaseResponse> purchaseHistory(CompanyVendor vendor, Pageable pageable) {
        UUID sellerClientId = vendor.getPlatformClientId();
        if (sellerClientId == null) {
            return Page.empty(pageable);
        }

        Page<Order> orders = orderRepository.findAllByClientIdAndSellerClientIdAndPlacedAtIsNotNullOrderByPlacedAtDesc(
                vendor.getClientId(), sellerClientId, pageable);
        if (orders.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, orders.getTotalElements());
        }

        Map<UUID, List<OrderItem>> linesByOrderId = orderItemRepository
                .findAllByOrderIdInOrderByCreatedAtAsc(orders.map(Order::getId).getContent())
                .stream()
                .collect(Collectors.groupingBy(item -> item.getOrder().getId()));

        return orders.map(order ->
                VendorPurchaseResponse.from(order, linesByOrderId.getOrDefault(order.getId(), List.of())));
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
