package com.procurepal_services.stock_bridge_api.marketplace;

import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.PaymentStatus;
import com.procurepal_services.stock_bridge_api.marketplace.dto.AdvanceOrderStatusRequest;
import com.procurepal_services.stock_bridge_api.marketplace.dto.MarketplaceCustomerResponse;
import com.procurepal_services.stock_bridge_api.order.InvalidOrderTransitionException;
import com.procurepal_services.stock_bridge_api.order.OrderLifecycleService;
import com.procurepal_services.stock_bridge_api.order.OrderNotFoundException;
import com.procurepal_services.stock_bridge_api.order.OrderResponseAssembler;
import com.procurepal_services.stock_bridge_api.order.dto.OrderResponse;
import com.procurepal_services.stock_bridge_api.order.dto.OrderSummaryResponse;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.OrderRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ProcurePal's fulfilment queue and customer list.
 *
 * <h2>The single most likely bug in this module, and what prevents it</h2>
 * {@code orders.client_id} is the BUYER. Under ProcurePal's own tenant filter, every
 * query in this class returns zero rows - not an error, just an empty queue that
 * looks like "no orders today". Every read therefore runs inside
 * {@link PlatformOwnerGuard#readAcrossTenants}, which asserts platform ownership,
 * lifts the Hibernate filter and restores it in a finally block. Nothing here calls
 * {@code Session.disableFilter} directly, and nothing here should.
 *
 * <h2>Two gates, not one</h2>
 * The controller carries {@code @PreAuthorize("hasAuthority('MANAGE_MARKETPLACE_ORDERS')")}
 * AND calls {@code requirePlatformOwner()}. The permission alone proves nothing:
 * permissions hang off global roles, so every tenant's OWNER holds it. Note that
 * readAcrossTenants performs the ownership check itself, so the two are not merely
 * belt-and-braces - the escape hatch cannot be opened by a non-operator even if a
 * controller forgets.
 */
@Service
@RequiredArgsConstructor
public class MarketplaceOrderAdminService {

    private final OrderRepository orderRepository;
    private final ClientRepository clientRepository;
    private final PlatformOwnerGuard platformOwnerGuard;
    private final OrderLifecycleService orderLifecycleService;
    private final OrderResponseAssembler orderResponseAssembler;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public Page<OrderSummaryResponse> queue(
            OrderStatus status,
            PaymentStatus paymentStatus,
            UUID clientId,
            String query,
            OffsetDateTime from,
            OffsetDateTime to,
            Pageable pageable) {
        platformOwnerGuard.requirePlatformOwner();

        // Company names live on clients, which Order has no association to (client_id
        // is a raw column, deliberately - see the Order entity). Resolving matching
        // buyers to ids first keeps the search working without inventing a join.
        List<UUID> matchingClientIds = query == null || query.isBlank()
                ? List.of()
                : clientRepository.findAll().stream()
                        .filter(client -> client.getName() != null
                                && client.getName().toLowerCase().contains(query.trim().toLowerCase()))
                        .map(Client::getId)
                        .toList();

        Specification<Order> specification = MarketplaceOrderSpecifications.forQueue(
                status, paymentStatus, clientId, query, matchingClientIds, from, to);

        return platformOwnerGuard.readAcrossTenants(() -> orderRepository
                .findAll(specification, pageable)
                .map(order -> orderResponseAssembler.summary(order, true)));
    }

    @Transactional(readOnly = true)
    public OrderResponse get(UUID orderId) {
        Order order = requireOrder(orderId);
        return orderResponseAssembler.detail(order, true);
    }

    /**
     * Advancing an order through fulfilment. Three rules, all of them enforced here
     * rather than in the UI:
     * <ul>
     *   <li>The transition must be one {@link OrderStatus#canTransitionTo} allows -
     *       the state machine lives on the enum and is never re-encoded.</li>
     *   <li>DELIVERED -> RECEIVED is refused. It is the buyer asserting the goods are
     *       in their store, and it writes stock into their inventory
     *       ({@link OrderStatus#isBuyerDriven()}); ProcurePal claiming it on their
     *       behalf would be inventing inventory.</li>
     *   <li>Cancelling at or after PLACED gives the buyer's incoming stock back, which
     *       OrderLifecycleService handles so it cannot be forgotten on one path.</li>
     * </ul>
     */
    @Transactional
    public OrderResponse advanceStatus(UUID orderId, AdvanceOrderStatusRequest request, UUID actingUserId) {
        Order order = lockOrder(orderId);

        if (order.getStatus().isBuyerDriven()) {
            throw new InvalidOrderTransitionException(
                    "Only the customer can confirm receipt of order " + order.getOrderNumber() + ".");
        }
        if (request.status() == OrderStatus.RECEIVED) {
            throw new InvalidOrderTransitionException(
                    "RECEIVED is set by the customer when they sign for the delivery, not from here.");
        }
        orderLifecycleService.transition(order, request.status(), request.note(), actingUserId);
        return orderResponseAssembler.detail(order, true);
    }

    /**
     * Settling a pay-on-delivery order once the rider hands the cash in. Does not
     * touch fulfilment status: the goods and the money move on independent axes, and
     * a COD order is routinely DELIVERED before the float is reconciled.
     */
    @Transactional
    public OrderResponse recordPaymentReceived(UUID orderId, UUID actingUserId) {
        Order order = lockOrder(orderId);

        if (order.getPaymentStatus() == PaymentStatus.PAID) {
            // Idempotent: two staff pressing the same button must not double-count
            // anything or produce a second "we have your payment" notification.
            return orderResponseAssembler.detail(order, true);
        }
        if (order.getPaymentStatus() != PaymentStatus.ON_DELIVERY) {
            throw new InvalidOrderTransitionException(
                    "Order " + order.getOrderNumber()
                            + " is not a pay-on-delivery order; its payment is handled by the provider.");
        }
        order.setPaymentStatus(PaymentStatus.PAID);
        orderLifecycleService.notifyPaymentReceived(order);
        return orderResponseAssembler.detail(order, true);
    }

    /**
     * ProcurePal's customer list. The per-row aggregates are one query each, which is
     * an N+1 over a page of 20 - accepted deliberately: the alternative is a bespoke
     * projection query that would have to be kept in step with the order state
     * machine, for a screen ops opens a few times a day.
     */
    @Transactional(readOnly = true)
    public Page<MarketplaceCustomerResponse> customers(String query, Pageable pageable) {
        platformOwnerGuard.requirePlatformOwner();

        return platformOwnerGuard.readAcrossTenants(() -> {
            List<Client> matches = clientRepository.findAll().stream()
                    // The operator is not its own customer.
                    .filter(client -> !client.isPlatformOwner())
                    .filter(client -> query == null
                            || query.isBlank()
                            || matches(client, query.trim().toLowerCase()))
                    .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                    .toList();

            int from = (int) Math.min(pageable.getOffset(), matches.size());
            int to = Math.min(from + pageable.getPageSize(), matches.size());
            List<MarketplaceCustomerResponse> page =
                    matches.subList(from, to).stream().map(this::toCustomer).toList();
            return new org.springframework.data.domain.PageImpl<>(page, pageable, matches.size());
        });
    }

    private MarketplaceCustomerResponse toCustomer(Client client) {
        OffsetDateTime lastOrderAt = orderRepository
                .findAllByClientIdOrderByCreatedAtDesc(client.getId(), PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .map(Order::getCreatedAt)
                .orElse(null);

        return new MarketplaceCustomerResponse(
                client.getId(),
                client.getName(),
                client.getSlug(),
                client.getPhone(),
                client.getAdminContactEmail(),
                client.getPaymentTerms(),
                client.isActive(),
                orderRepository.countByClientId(client.getId()),
                orderRepository.sumTotalByClientIdAndPaymentStatus(client.getId(), PaymentStatus.PAID),
                lastOrderAt,
                client.getCreatedAt());
    }

    /**
     * Loaded inside readAcrossTenants because the order belongs to a buyer. Note that
     * a by-id load would technically bypass the tenant filter anyway - it goes through
     * the guard regardless, so every cross-tenant read in this class is visible in one
     * place rather than depending on a Hibernate subtlety the next reader has to know.
     */
    private Order requireOrder(UUID orderId) {
        return platformOwnerGuard
                .readAcrossTenants(() -> orderRepository.findById(orderId))
                .orElseThrow(OrderNotFoundException::new);
    }

    /**
     * Row-locks the order before any mutating path reads its status, so two staff (or
     * two tabs) hitting Dispatch at the same instant serialise here rather than both
     * seeing PROCESSING. The loser then fails the {@code canTransitionTo} check, which
     * is what makes "advancing to OUT_FOR_DELIVERY writes the OUT stock movements"
     * exactly-once rather than merely usually-once.
     *
     * Ownership is still proven first - {@code requirePlatformOwner} runs before the
     * lock is taken - and the load itself is by id, which Hibernate does not apply the
     * tenant filter to, so it reaches the buyer's row the same way requireOrder does.
     */
    private Order lockOrder(UUID orderId) {
        platformOwnerGuard.requirePlatformOwner();
        Order order = entityManager.find(Order.class, orderId, LockModeType.PESSIMISTIC_WRITE);
        if (order == null) {
            throw new OrderNotFoundException();
        }
        return order;
    }

    private static boolean matches(Client client, String needle) {
        return (client.getName() != null && client.getName().toLowerCase().contains(needle))
                || (client.getSlug() != null && client.getSlug().toLowerCase().contains(needle))
                || (client.getAdminContactEmail() != null
                        && client.getAdminContactEmail().toLowerCase().contains(needle));
    }
}
