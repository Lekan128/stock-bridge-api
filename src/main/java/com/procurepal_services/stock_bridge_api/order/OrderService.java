package com.procurepal_services.stock_bridge_api.order;

import com.procurepal_services.stock_bridge_api.address.DeliveryAddressService;
import com.procurepal_services.stock_bridge_api.cart.CartService;
import com.procurepal_services.stock_bridge_api.entity.Branch;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.DeliveryAddress;
import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.OrderItem;
import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.PaymentMethod;
import com.procurepal_services.stock_bridge_api.entity.PaymentStatus;
import com.procurepal_services.stock_bridge_api.order.dto.CancelOrderRequest;
import com.procurepal_services.stock_bridge_api.order.dto.OrderItemMatchSuggestionResponse;
import com.procurepal_services.stock_bridge_api.order.dto.OrderResponse;
import com.procurepal_services.stock_bridge_api.order.dto.OrderSummaryResponse;
import com.procurepal_services.stock_bridge_api.order.dto.PlaceOrderRequest;
import com.procurepal_services.stock_bridge_api.order.dto.ReceiveOrderRequest;
import com.procurepal_services.stock_bridge_api.order.dto.ReorderResponse;
import com.procurepal_services.stock_bridge_api.repository.BranchRepository;
import com.procurepal_services.stock_bridge_api.repository.OrderItemRepository;
import com.procurepal_services.stock_bridge_api.repository.OrderRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The buyer's side of an order: placing one, reading their own, cancelling, and
 * confirming receipt.
 *
 * <h2>Isolation</h2>
 * Every read goes through a finder that carries {@code client_id} explicitly
 * ({@code findByIdAndClientId}), not just through the Hibernate tenant filter. The
 * filter is layer one and would be enough today; the explicit predicate is what keeps
 * this correct if a future caller ever runs with the filter lifted - which ProcurePal's
 * fulfilment queue legitimately does, in this very same table.
 *
 * <h2>What order creation trusts from the client</h2>
 * The payment method, the delivery address and a note. Everything else - which
 * products, how many, at what price, what delivery costs, whether the order clears
 * the minimum - is recomputed here from the server-side cart and the live catalog.
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final BranchRepository branchRepository;
    private final CartService cartService;
    private final CheckoutService checkoutService;
    private final DeliveryAddressService deliveryAddressService;
    private final OrderLifecycleService orderLifecycleService;
    private final IncomingStockService incomingStockService;
    private final CatalogStockService catalogStockService;
    private final OrderNumberAllocator orderNumberAllocator;
    private final OrderResponseAssembler orderResponseAssembler;

    /**
     * Turns the cart into orders - plural, since a basket holding several sellers'
     * products splits into ONE ORDER PER SELLER.
     *
     * <h2>The split, in order</h2>
     * <ol>
     *   <li>Price the whole basket once, and group it by seller
     *       ({@link CheckoutService#price()}). Blockers, the payment-method gate and the
     *       stock re-check all run against the WHOLE basket first, so a cart that cannot
     *       be checked out fails before a single order row exists.</li>
     *   <li>Mint one checkout group id. Every order below carries it, and it is what
     *       lets one Monnify transaction settle all of them - see
     *       {@link Order#getCheckoutGroupId()}.</li>
     *   <li>Write one Order per group, each with its own order number, seller, subtotal,
     *       delivery fee and total, and its own lines with the seller's commission rate
     *       stamped on them.</li>
     * </ol>
     * All of it in one transaction: a basket that becomes two orders and then fails must
     * leave neither behind, or the buyer has paid-for goods and an orphan.
     *
     * <h2>Why the whole basket is validated before anything is written</h2>
     * The stock lock in step 1 covers every line across every seller. Doing it per group
     * as the orders were written would leave a window where seller A's order exists and
     * seller B's line turns out to be oversold - and then the buyer either loses half a
     * basket or the transaction rolls back after a partial notification has already
     * fired. One validation pass, then one write pass.
     *
     * <h2>What the buyer sees back</h2>
     * The FIRST order of the group, detailed, with its {@code relatedOrders} listing the
     * siblings. The response is a single OrderResponse rather than a list because every
     * existing caller - the confirmation page, the payment handoff, the tests - expects
     * one order, and because the payment handoff genuinely does need one anchor order to
     * open the checkout against. The confirmation screen reads {@code checkoutGroupId}
     * and {@code relatedOrders} to explain that one basket produced several orders.
     */
    @Transactional
    public OrderResponse place(PlaceOrderRequest request, UUID actingUserId) {
        UUID clientId = requireTenantId();
        Client client = checkoutService.requireClient();
        CheckoutService.PricedCart priced = checkoutService.price();

        if (!priced.isCheckoutable()) {
            throw new CheckoutNotAllowedException(String.join(" ", priced.blockers()));
        }
        checkoutService.requirePaymentMethodAllowed(request.paymentMethod(), client, priced);

        // Third and final availability check, and the only one that is safe under
        // concurrency: it locks each catalog row for the rest of this transaction before
        // recomputing what is left to sell. The quote and price() both read without a
        // lock, so without this two companies could each be told yes for the last ten
        // bags within the same second.
        //
        // Across ALL sellers, before any order is written - see the javadoc.
        for (CheckoutService.PricedLine line : priced.lines()) {
            catalogStockService.requireSellable(
                    line.product().getId(), line.quantity(), line.product().getName());
        }

        DeliveryAddress address = resolveAddress(request);
        Branch branch = branchRepository.findByClientIdAndDefaultBranchTrue(clientId).orElse(null);

        // Pay-on-delivery skips PENDING_PAYMENT entirely: there is no payment to wait
        // for, so the order is real (and the buyer's incoming stock appears) the moment
        // it is placed. Monnify orders exist but are not yet commitments - no incoming
        // stock until a verified payment arrives (contract §5).
        boolean payOnDelivery = request.paymentMethod().isPayOnDelivery();
        OrderStatus initialStatus = payOnDelivery ? OrderStatus.PLACED : OrderStatus.PENDING_PAYMENT;

        // One id for the whole shopping trip, minted before the first insert so every
        // order of the group carries the same value. Random rather than derived from any
        // one order, so no member of the group is structurally special and cancelling
        // one cannot orphan the rest (V12__order_splitting.sql).
        UUID checkoutGroupId = UUID.randomUUID();

        List<Order> created = new ArrayList<>(priced.groups().size());

        for (CheckoutService.PricedSellerGroup group : priced.groups()) {
            Order order = Order.builder()
                    .orderNumber(orderNumberAllocator.allocate())
                    // Who is selling: read off the catalog products, never assumed to be
                    // ProcurePal. Since V11 any vendor client can own listed products,
                    // and the split above is what guarantees every line of THIS order
                    // shares this one seller.
                    .sellerClientId(group.sellerId())
                    .checkoutGroupId(checkoutGroupId)
                    .placedBy(actingUserId)
                    .branchId(branch == null ? null : branch.getId())
                    .status(initialStatus)
                    .paymentStatus(payOnDelivery ? PaymentStatus.ON_DELIVERY : PaymentStatus.PENDING)
                    .paymentMethod(request.paymentMethod())
                    .currency("NGN")
                    // The GROUP's figures, not the basket's. Each order is a
                    // self-contained contract: its own goods, its own delivery fee (see
                    // CheckoutService for why the fee is per seller), its own total.
                    .subtotal(group.subtotal())
                    .deliveryFee(group.deliveryFee())
                    .total(group.total())
                    // The delivery snapshot is identical across the group - one basket,
                    // one address - and is copied onto each order rather than shared,
                    // for the same reason it was copied at all: an order is a contract,
                    // and editing an address must never rewrite a shipment.
                    .deliveryAddressId(address.getId())
                    .deliveryLabel(address.getLabel())
                    .deliveryContactName(address.getContactName())
                    .deliveryContactPhone(address.getContactPhone())
                    .deliveryAddressLine1(address.getAddressLine1())
                    .deliveryAddressLine2(address.getAddressLine2())
                    .deliveryCity(address.getCity())
                    .deliveryState(address.getState())
                    .deliveryLandmark(address.getLandmark())
                    .deliveryNotes(address.getDeliveryNotes())
                    .customerNote(blankToNull(request.customerNote()))
                    .build();
            order = orderRepository.saveAndFlush(order);

            for (CheckoutService.PricedLine line : group.lines()) {
                orderItemRepository.save(OrderItem.builder()
                        .order(order)
                        .productId(line.product().getId())
                        // Snapshots, not references. What the buyer saw and agreed to pay
                        // must survive a rename, a repricing and a delisting.
                        .productName(line.product().getName())
                        .productSku(line.product().getSku())
                        .unitOfMeasure(line.product().getUnitOfMeasure())
                        .imageUrl(line.product().getImageUrl())
                        .unitPrice(line.unitPrice())
                        .quantity(line.quantity())
                        .receivedQuantity(0)
                        .lineTotal(line.lineTotal())
                        // The seller's rate AT SALE TIME, resolved once during pricing.
                        // Null for ProcurePal's own lines - commission is a fact about a
                        // third-party sale, and null means "none applies", never 0%.
                        .commissionRate(group.commissionRate())
                        .build());
            }
            orderItemRepository.flush();

            orderLifecycleService.recordEvent(
                    order,
                    null,
                    initialStatus,
                    payOnDelivery ? "Order placed (pay on delivery)" : "Awaiting payment",
                    actingUserId);

            if (payOnDelivery) {
                orderLifecycleService.enterPlaced(order, true, null, actingUserId);
            }
            created.add(order);
        }

        cartService.clearItems(priced.cart().getId());
        return orderResponseAssembler.detail(created.getFirst(), false);
    }

    @Transactional(readOnly = true)
    public Page<OrderSummaryResponse> list(OrderStatus status, Pageable pageable) {
        UUID clientId = requireTenantId();
        Page<Order> page = status == null
                ? orderRepository.findAllByClientIdOrderByCreatedAtDesc(clientId, pageable)
                : orderRepository.findAllByClientIdAndStatusOrderByCreatedAtDesc(clientId, status, pageable);
        return page.map(order -> orderResponseAssembler.summary(order, false));
    }

    @Transactional(readOnly = true)
    public OrderResponse get(UUID orderId) {
        return orderResponseAssembler.detail(requireOwnOrder(orderId), false);
    }

    @Transactional
    public OrderResponse cancel(UUID orderId, CancelOrderRequest request, UUID actingUserId) {
        Order order = requireOwnOrder(orderId);
        // Narrower than the state machine allows on purpose: OrderStatus permits
        // CANCELLED from CONFIRMED and PROCESSING too, but those belong to ProcurePal -
        // once picking has started, a buyer pulling out is a conversation, not a button.
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT && order.getStatus() != OrderStatus.PLACED) {
            throw new InvalidOrderTransitionException(
                    "Order " + order.getOrderNumber() + " can no longer be cancelled. Contact ProcurePal support.");
        }
        orderLifecycleService.transition(
                order,
                OrderStatus.CANCELLED,
                blankToNull(request == null ? null : request.reason()),
                actingUserId);
        return orderResponseAssembler.detail(order, false);
    }

    /**
     * The buyer signing for goods. This is the transition ProcurePal may never make on
     * their behalf ({@link OrderStatus#isBuyerDriven()}): it writes stock into someone
     * else's inventory.
     *
     * Partial receipt leaves the order at DELIVERED with the remainder still incoming.
     * The order only becomes RECEIVED when every line is fully accounted for, so
     * "received" always means "all of it is in my store".
     */
    @Transactional
    public OrderResponse receive(UUID orderId, ReceiveOrderRequest request, UUID actingUserId) {
        Order order = requireOwnOrder(orderId);
        if (order.getStatus() != OrderStatus.DELIVERED) {
            throw new InvalidOrderTransitionException(
                    "Order " + order.getOrderNumber() + " is not marked delivered yet, so it cannot be received.");
        }

        List<OrderItem> items = orderItemRepository.findAllByOrderIdOrderByCreatedAtAsc(orderId);
        boolean explicitLines = request != null && request.lines() != null && !request.lines().isEmpty();
        int applied = 0;

        if (explicitLines) {
            for (ReceiveOrderRequest.ReceiveOrderLine line : request.lines()) {
                // Re-resolved against THIS order rather than trusted from the body: an
                // order item id from someone else's order must never be receivable here.
                OrderItem item = orderItemRepository
                        .findByIdAndOrderId(line.orderItemId(), orderId)
                        .orElseThrow(OrderNotFoundException::new);
                applied += incomingStockService.receive(
                        order,
                        item,
                        line.quantity(),
                        actingUserId,
                        line.linkToExistingProductId(),
                        line.packagingUnit(),
                        line.packagingSize(),
                        Boolean.TRUE.equals(line.saveAsSupplierDefault()));
            }
        } else {
            // No lines given means "all of it", which is what the button on the order
            // page does and what most deliveries actually are.
            for (OrderItem item : items) {
                applied += incomingStockService.receive(
                        order, item, item.outstandingQuantity(), actingUserId, null, null, null, false);
            }
        }

        if (applied == 0) {
            throw new CheckoutNotAllowedException("There is nothing left to receive on this order.");
        }

        orderItemRepository.flush();
        boolean fullyReceived = orderItemRepository.findAllByOrderIdOrderByCreatedAtAsc(orderId).stream()
                .allMatch(item -> item.outstandingQuantity() == 0);
        if (fullyReceived) {
            orderLifecycleService.markReceived(order, actingUserId);
        }
        return orderResponseAssembler.detail(order, false);
    }

    /**
     * The section 7.2 duplicate nudge for this order's "confirm receipt" screen - see {@link
     * IncomingStockService#suggestMatches}. Read-only and safe to call regardless of status; the
     * result is simply empty once every line has either matched cleanly or already been received.
     */
    @Transactional(readOnly = true)
    public List<OrderItemMatchSuggestionResponse> receiveSuggestions(UUID orderId) {
        return incomingStockService.suggestMatches(requireOwnOrder(orderId));
    }

    /**
     * Rebuild the basket from a past order. Best-effort by design - see
     * {@link ReorderResponse} for why a discontinued line must not fail the whole
     * request.
     */
    @Transactional
    public ReorderResponse reorder(UUID orderId, UUID actingUserId) {
        Order order = requireOwnOrder(orderId);
        List<ReorderResponse.SkippedLine> skipped = new ArrayList<>();
        int added = 0;

        for (OrderItem item : orderItemRepository.findAllByOrderIdOrderByCreatedAtAsc(orderId)) {
            if (cartService.tryAddLine(item.getProductId(), item.getQuantity(), actingUserId)) {
                added++;
            } else {
                skipped.add(new ReorderResponse.SkippedLine(
                        item.getProductId(),
                        item.getProductName(),
                        "No longer available on the marketplace."));
            }
        }
        return new ReorderResponse(cartService.getCart(), added, List.copyOf(skipped));
    }

    private Order requireOwnOrder(UUID orderId) {
        return orderRepository
                .findByIdAndClientId(orderId, requireTenantId())
                .orElseThrow(OrderNotFoundException::new);
    }

    /**
     * Exactly one of the two forms, resolved server-side. An inline address is
     * ALWAYS persisted (there is nowhere else to put a snapshot's source, and
     * delivery_address_id has to point somewhere for "reuse this address" to work);
     * saveAddress only decides whether it stays in the visible address book.
     */
    private DeliveryAddress resolveAddress(PlaceOrderRequest request) {
        if (request.newAddress() != null) {
            DeliveryAddress created = deliveryAddressService.createEntity(request.newAddress());
            if (!Boolean.TRUE.equals(request.saveAddress())) {
                created.setActive(false);
                created.setDefaultAddress(false);
            }
            return created;
        }
        return deliveryAddressService
                .resolveForCheckout(request.deliveryAddressId())
                .orElseThrow(() -> new CheckoutNotAllowedException(
                        "Choose a delivery address before placing this order."));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static UUID requireTenantId() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context set");
        }
        return tenantId;
    }
}
