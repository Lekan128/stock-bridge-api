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
import com.procurepal_services.stock_bridge_api.vendor.VendorGuard;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A SELLER's fulfilment queue and customer list - ProcurePal's, or a vendor's.
 *
 * <h2>The single most likely bug in this module, and what prevents it</h2>
 * {@code orders.client_id} is the BUYER. Under the seller's own tenant filter, every
 * query in this class returns zero rows - not an error, just an empty queue that
 * looks like "no orders today". Every read therefore runs inside
 * {@link VendorGuard#readOwnSales}, which asserts the caller may sell, lifts the
 * Hibernate filter and restores it in a finally block. Nothing here calls
 * {@code Session.disableFilter} directly, and nothing here should.
 *
 * <h2>THE OTHER MOST LIKELY BUG: lifting the filter without replacing it</h2>
 * {@code readOwnSales} lifts tenant isolation and puts NOTHING in its place. That is
 * safe only because every query below adds {@code seller_client_id = <me>} by hand.
 * A single read inside that block without the predicate would hand one vendor every
 * other vendor's orders - customers, delivery addresses, revenue - which is the worst
 * failure this feature can produce and would be completely silent. The predicate is
 * therefore built in exactly one place, {@link MarketplaceOrderSpecifications#forQueue},
 * which takes the seller id as a REQUIRED first argument rather than an optional
 * filter, and every mutating path re-resolves its order through
 * {@link #requireOwnOrder}.
 *
 * <h2>Two gates, not one</h2>
 * The controller carries {@code @PreAuthorize("hasAuthority('MANAGE_MARKETPLACE_ORDERS')")}
 * AND this service calls {@code requireSeller()}. The permission alone proves nothing,
 * and proves LESS than it used to: it hangs off global roles, so every tenant's OWNER
 * holds it - and the VENDOR role now holds it too, by design. The permission answers
 * "does this person do fulfilment"; the guard answers "for which company"; and the
 * {@code seller_client_id} predicate answers "which rows are theirs". All three are
 * required and none is redundant.
 *
 * <h2>requireSeller, not requireVendor - and not requirePlatformOwner</h2>
 * This surface belongs to everyone who sells. Using {@code requireVendor()} would lock
 * ProcurePal out of its own fulfilment queue; keeping {@code requirePlatformOwner()}
 * would mean vendors could never see the orders placed with them, which is the entire
 * point of the module.
 *
 * <h2>ProcurePal is not privileged HERE</h2>
 * Being the platform owner does not widen this queue. ProcurePal sees orders where it
 * is the seller, exactly as before, and cannot advance a vendor's order through these
 * endpoints - the predicate is the same predicate. That is deliberate: dispatching
 * another company's goods is not an operator capability, it is a fulfilment action
 * that only the party holding the stock can honestly take. An operator override, if
 * one is ever needed, belongs on a super-admin surface with its own audit trail, not
 * quietly folded into the normal queue.
 */
@Service
@RequiredArgsConstructor
public class MarketplaceOrderAdminService {

    private final OrderRepository orderRepository;
    private final ClientRepository clientRepository;
    private final VendorGuard vendorGuard;
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
        UUID sellerId = vendorGuard.requireSeller().getId();

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
                sellerId, status, paymentStatus, clientId, query, matchingClientIds, from, to);

        return vendorGuard.readOwnSales(() -> orderRepository
                .findAll(specification, pageable)
                .map(order -> orderResponseAssembler.summary(order, true)));
    }

    @Transactional(readOnly = true)
    public OrderResponse get(UUID orderId) {
        UUID sellerId = vendorGuard.requireSeller().getId();
        return orderResponseAssembler.detail(requireOwnOrder(orderId, sellerId), true);
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
        Order order = lockOwnOrder(orderId);

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
        Order order = lockOwnOrder(orderId);

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
    /**
     * The seller's customer list.
     *
     * <h2>Who appears depends on who is asking, and deliberately so</h2>
     * <ul>
     *   <li><b>A vendor</b> sees only the companies that have actually placed an order
     *       with THEM. Anything wider would hand a third party the platform's entire
     *       client list - names, contact emails, phone numbers and payment terms - which
     *       is a competitor intelligence feed, not a customer list. This is the
     *       cross-seller leak this module exists to prevent, and it is not hypothetical:
     *       the previous implementation was a bare {@code clientRepository.findAll()}.</li>
     *   <li><b>The platform owner</b> keeps exactly the list it had before vendors
     *       existed: every buying COMPANY on the platform, whether or not they have
     *       ordered yet. ProcurePal runs the marketplace and legitimately administers
     *       its tenants, and narrowing this would have silently emptied an operations
     *       screen that has always shown prospects alongside customers.</li>
     * </ul>
     *
     * <p>Note that VENDOR accounts are excluded from the platform owner's list. That is
     * what PRESERVES the old behaviour rather than departing from it: before V11 every
     * clients row was a buying company, so "every client but me" and "every buying
     * company but me" named the same set. Vendors cannot buy at all, so a vendor in a
     * customer list would be new noise, not continuity.
     *
     * <h2>The aggregates are scoped to the seller for everyone</h2>
     * Order count, revenue and last-order date are measured over the CALLER's own sales,
     * including for ProcurePal. That both prevents a vendor's revenue being disclosed
     * through another seller's screen and preserves ProcurePal's historical numbers
     * exactly - every order placed before this module existed was ProcurePal's, so
     * "their orders with me" and "all their orders" agree on every pre-vendor row.
     *
     * <h2>M6 re-examined this and left it alone, on purpose</h2>
     * M6 narrowed every metric on {@code /api/marketplace/admin/analytics/**} to
     * ProcurePal's own sales, the top-customers RANKING included. This roster was
     * deliberately not narrowed with it, and the two decisions are consistent rather than
     * in tension: a ranking is a revenue statement about named companies, so it must be
     * scoped to the money the reader actually took; a roster is an ops list of who exists
     * to sell to. Narrowing the roster would delete a working screen - the prospects
     * ProcurePal has never sold to would vanish - to fix a disclosure problem it does not
     * have, because the money columns beside each name were already seller-scoped, as the
     * section above says. See {@code MarketplaceAnalyticsService} for the per-metric ruling
     * this one is quoted in.
     */
    @Transactional(readOnly = true)
    public Page<MarketplaceCustomerResponse> customers(String query, Pageable pageable) {
        Client seller = vendorGuard.requireSeller();
        UUID sellerId = seller.getId();
        boolean isPlatformOwner = seller.isPlatformOwner();

        return vendorGuard.readOwnSales(() -> {
            Set<UUID> buyerIds = orderRepository
                    .findAllBySellerClientIdOrderByCreatedAtDesc(sellerId, Pageable.unpaged())
                    .stream()
                    .map(Order::getClientId)
                    .collect(java.util.stream.Collectors.toSet());

            List<Client> matches = clientRepository.findAll().stream()
                    .filter(client -> isPlatformOwner
                            // Every buying company, as before - but never another
                            // seller, and never itself.
                            ? !client.isPlatformOwner() && !client.isVendor()
                            // A vendor sees only companies that bought from them.
                            : buyerIds.contains(client.getId()))
                    // A seller is never its own customer, on either branch.
                    .filter(client -> !client.getId().equals(sellerId))
                    .filter(client -> query == null
                            || query.isBlank()
                            || matches(client, query.trim().toLowerCase()))
                    .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                    .toList();

            int from = (int) Math.min(pageable.getOffset(), matches.size());
            int to = Math.min(from + pageable.getPageSize(), matches.size());
            List<MarketplaceCustomerResponse> page =
                    matches.subList(from, to).stream().map(client -> toCustomer(client, sellerId)).toList();
            return new org.springframework.data.domain.PageImpl<>(page, pageable, matches.size());
        });
    }

    /**
     * One customer row, with the aggregates measured against THIS SELLER's orders only.
     *
     * <p>The counts and revenue used to be {@code countByClientId} - every order that
     * buyer ever placed, with anyone. Left alone, a vendor would be shown a buying
     * company's total spend across the whole marketplace, including with their
     * competitors. Filtered in memory over the seller's own orders rather than by adding
     * two more repository methods, because this screen is already documented as an
     * accepted N+1 over a page of 20 and the seller's order list is loaded once above.
     */
    private MarketplaceCustomerResponse toCustomer(Client client, UUID sellerId) {
        List<Order> ordersWithThisSeller = orderRepository
                .findAllBySellerClientIdOrderByCreatedAtDesc(sellerId, Pageable.unpaged())
                .stream()
                .filter(order -> order.getClientId().equals(client.getId()))
                .toList();

        OffsetDateTime lastOrderAt = ordersWithThisSeller.stream()
                .map(Order::getCreatedAt)
                .max(OffsetDateTime::compareTo)
                .orElse(null);

        BigDecimal paidRevenue = ordersWithThisSeller.stream()
                .filter(order -> order.getPaymentStatus() == PaymentStatus.PAID)
                .map(Order::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new MarketplaceCustomerResponse(
                client.getId(),
                client.getName(),
                client.getSlug(),
                client.getPhone(),
                client.getAdminContactEmail(),
                client.getPaymentTerms(),
                client.isActive(),
                ordersWithThisSeller.size(),
                paidRevenue,
                lastOrderAt,
                client.getCreatedAt());
    }

    /**
     * One order, proven to belong to the CALLING SELLER.
     *
     * <p>{@code findByIdAndSellerClientId} rather than {@code findById} plus a check
     * afterwards: the predicate is in the query, so there is no window in which the
     * wrong row is in memory and no way for a later edit to drop the comparison. A
     * vendor asking for another seller's order id gets the same 404 as a nonexistent
     * one, so this cannot be used to probe which order numbers are real.
     *
     * <p>Runs inside readOwnSales because the order belongs to a BUYER, so the seller's
     * own tenant filter would exclude it. The seller predicate is what replaces that
     * isolation - see the class javadoc.
     */
    private Order requireOwnOrder(UUID orderId, UUID sellerId) {
        return vendorGuard
                .readOwnSales(() -> orderRepository.findByIdAndSellerClientId(orderId, sellerId))
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
    private Order lockOwnOrder(UUID orderId) {
        UUID sellerId = vendorGuard.requireSeller().getId();
        // Ownership BEFORE the lock, and through the seller-scoped finder, so a caller
        // cannot even take a row lock on another seller's order - let alone read it.
        requireOwnOrder(orderId, sellerId);

        Order order = entityManager.find(Order.class, orderId, LockModeType.PESSIMISTIC_WRITE);
        if (order == null) {
            throw new OrderNotFoundException();
        }
        // Re-checked after the lock, not merely before it. The check above proves the
        // row was ours a moment ago; this proves it is ours now, on the instance the
        // mutation is about to be applied to. Cheap, and it closes the gap between the
        // two loads by hand rather than by argument.
        if (!sellerId.equals(order.getSellerClientId())) {
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
