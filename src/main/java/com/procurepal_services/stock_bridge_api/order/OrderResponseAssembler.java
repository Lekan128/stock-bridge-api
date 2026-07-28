package com.procurepal_services.stock_bridge_api.order;

import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.OrderItem;
import com.procurepal_services.stock_bridge_api.entity.OrderStatusEvent;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.order.dto.OrderCustomerResponse;
import com.procurepal_services.stock_bridge_api.order.dto.OrderResponse;
import com.procurepal_services.stock_bridge_api.order.dto.OrderSummaryResponse;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.OrderItemRepository;
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
 */
@Component
@RequiredArgsConstructor
public class OrderResponseAssembler {

    private final OrderItemRepository orderItemRepository;
    private final OrderStatusEventRepository orderStatusEventRepository;
    private final UserRepository userRepository;
    private final ClientRepository clientRepository;

    /** {@code includeCustomer} is true only for ProcurePal's views - see OrderCustomerResponse. */
    @Transactional(readOnly = true)
    public OrderResponse detail(Order order, boolean includeCustomer) {
        List<OrderItem> items = orderItemRepository.findAllByOrderIdOrderByCreatedAtAsc(order.getId());
        List<OrderStatusEvent> events = orderStatusEventRepository.findAllByOrderIdOrderByCreatedAtAsc(order.getId());
        return OrderResponse.of(order, items, events, customerOf(order, includeCustomer), placedByUsername(order));
    }

    @Transactional(readOnly = true)
    public OrderSummaryResponse summary(Order order, boolean includeCustomer) {
        return OrderSummaryResponse.of(
                order,
                (int) orderItemRepository.countByOrderId(order.getId()),
                customerOf(order, includeCustomer));
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
