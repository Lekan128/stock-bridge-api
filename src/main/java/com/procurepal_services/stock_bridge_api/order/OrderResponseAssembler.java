package com.procurepal_services.stock_bridge_api.order;

import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.OrderItem;
import com.procurepal_services.stock_bridge_api.entity.OrderStatusEvent;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.order.dto.OrderCustomerResponse;
import com.procurepal_services.stock_bridge_api.marketplace.SellerDirectory;
import com.procurepal_services.stock_bridge_api.order.dto.OrderResponse;
import com.procurepal_services.stock_bridge_api.order.dto.OrderSellerResponse;
import com.procurepal_services.stock_bridge_api.order.dto.OrderSummaryResponse;
import com.procurepal_services.stock_bridge_api.order.dto.SiblingOrderResponse;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.OrderItemRepository;
import com.procurepal_services.stock_bridge_api.repository.OrderRepository;
import com.procurepal_services.stock_bridge_api.repository.OrderStatusEventRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds order responses for both audiences. Shared so the buyer's order detail and
 * ProcurePal's fulfilment detail cannot drift into showing different truths about the
 * same order.
 *
 * {@code placedBy} is a raw UUID on the order (it crosses tenants when ProcurePal
 * reads it), so the username is resolved by an explicit by-id load, which is not
 * tenant-filtered. That is safe here and nowhere near a privilege: a username is
 * already visible to anyone who can see the order.
 *
 * <h2>The seller, and the siblings from the same checkout</h2>
 * Both are resolved by explicit by-id / by-group loads for the same reason: an order
 * is read by the buyer (under their tenant filter), by the seller and by ProcurePal
 * (both with the filter lifted), and none of those loads may depend on which. The
 * seller comes from {@link SellerDirectory#findSellerOfRecord} - the seller of record,
 * not the active-seller list - because a past order must still name a vendor who has
 * since been suspended.
 *
 * <h2>Why siblings appear on the detail response and not on summaries</h2>
 * The list projection deliberately carries only the seller, not the sibling orders. A
 * 50-row order history would otherwise issue one sibling query per row to render
 * information the list has no room for; the grouping belongs on the detail page and on
 * the confirmation screen, which are the two places a buyer asks "what happened to my
 * basket". Summaries carry {@code checkoutGroupId}, which is enough for a list to badge
 * rows from one trip without a second query.
 */
@Component
@RequiredArgsConstructor
public class OrderResponseAssembler {

    private final OrderItemRepository orderItemRepository;
    private final OrderStatusEventRepository orderStatusEventRepository;
    private final UserRepository userRepository;
    private final ClientRepository clientRepository;
    private final OrderRepository orderRepository;
    private final SellerDirectory sellerDirectory;

    /** {@code includeCustomer} is true only for the seller's views - see OrderCustomerResponse. */
    @Transactional(readOnly = true)
    public OrderResponse detail(Order order, boolean includeCustomer) {
        List<OrderItem> items = orderItemRepository.findAllByOrderIdOrderByCreatedAtAsc(order.getId());
        List<OrderStatusEvent> events = orderStatusEventRepository.findAllByOrderIdOrderByCreatedAtAsc(order.getId());
        return OrderResponse.of(
                order,
                items,
                events,
                customerOf(order, includeCustomer),
                placedByUsername(order),
                sellerOf(order),
                siblingsOf(order));
    }

    @Transactional(readOnly = true)
    public OrderSummaryResponse summary(Order order, boolean includeCustomer) {
        return OrderSummaryResponse.of(
                order,
                (int) orderItemRepository.countByOrderId(order.getId()),
                customerOf(order, includeCustomer),
                sellerOf(order));
    }

    private OrderSellerResponse sellerOf(Order order) {
        return OrderSellerResponse.from(
                sellerDirectory.findSellerOfRecord(order.getSellerClientId()).orElse(null));
    }

    /**
     * The other orders this checkout produced. Empty for the ordinary single-seller
     * basket, which is every order placed before V12 and most placed after it.
     *
     * <p>Scoped by checkout group alone, without a client_id predicate, and that is
     * safe for a specific reason: this is only ever reached from an order the caller
     * has ALREADY been authorised to read, and every order in a group shares one
     * buyer by construction - a group is one press of one button by one company. The
     * seller-facing callers see the siblings too, which is correct and intended: a
     * vendor being told "this order was part of a larger basket" is how they
     * understand a buyer's delivery expectations, and the sibling summary carries no
     * line items, no address and no customer.
     */
    private List<SiblingOrderResponse> siblingsOf(Order order) {
        if (order.getCheckoutGroupId() == null) {
            return List.of();
        }
        List<Order> group = orderRepository.findAllByCheckoutGroupIdOrderByOrderNumberAsc(order.getCheckoutGroupId());
        if (group.size() <= 1) {
            return List.of();
        }
        return group.stream()
                .filter(sibling -> !sibling.getId().equals(order.getId()))
                .map(sibling -> SiblingOrderResponse.of(sibling, sellerOf(sibling)))
                .toList();
    }

    private OrderCustomerResponse customerOf(Order order, boolean includeCustomer) {
        if (!includeCustomer) {
            return null;
        }
        return clientRepository.findById(order.getClientId()).map(OrderCustomerResponse::from).orElse(null);
    }

    private String placedByUsername(Order order) {
        if (order.getPlacedBy() == null) {
            return null;
        }
        return userRepository.findById(order.getPlacedBy()).map(User::getUsername).orElse(null);
    }
}
