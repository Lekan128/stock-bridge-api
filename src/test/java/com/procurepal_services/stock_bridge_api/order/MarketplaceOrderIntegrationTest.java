package com.procurepal_services.stock_bridge_api.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.address.dto.DeliveryAddressRequest;
import com.procurepal_services.stock_bridge_api.address.dto.DeliveryAddressResponse;
import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.cart.dto.AddCartItemRequest;
import com.procurepal_services.stock_bridge_api.cart.dto.CartResponse;
import com.procurepal_services.stock_bridge_api.cart.dto.MergeCartRequest;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.MovementType;
import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.PaymentMethod;
import com.procurepal_services.stock_bridge_api.entity.PaymentStatus;
import com.procurepal_services.stock_bridge_api.entity.PaymentTerms;
import com.procurepal_services.stock_bridge_api.entity.PaymentVerificationSource;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.marketplace.dto.AdvanceOrderStatusRequest;
import com.procurepal_services.stock_bridge_api.order.dto.CheckoutQuoteRequest;
import com.procurepal_services.stock_bridge_api.order.dto.CheckoutQuoteResponse;
import com.procurepal_services.stock_bridge_api.order.dto.OrderItemResponse;
import com.procurepal_services.stock_bridge_api.order.dto.OrderResponse;
import com.procurepal_services.stock_bridge_api.order.dto.OrderSummaryResponse;
import com.procurepal_services.stock_bridge_api.order.dto.PlaceOrderRequest;
import com.procurepal_services.stock_bridge_api.order.dto.ReceiveOrderRequest;
import com.procurepal_services.stock_bridge_api.order.dto.ReorderResponse;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.OrderRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.repository.StockMovementRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * The whole marketplace purchase loop, end to end: cart -> checkout -> order ->
 * fulfilment -> receipt into the buyer's inventory, plus the isolation and
 * idempotency properties the loop depends on.
 *
 * Runs against the local docker-compose Postgres like every other integration test
 * here (see AuthIntegrationTest for why local Postgres over Testcontainers), and
 * against the seeded ProcurePal catalog from
 * db/seed/V9001__seed_procurepal_marketplace.sql. Requires `docker compose up -d`.
 *
 * Every buyer is a freshly signed-up tenant rather than the shared `demo` one, so
 * tests cannot see each other's carts or orders and can be run in any order.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class MarketplaceOrderIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderPaymentApplication orderPaymentApplication;

    @Autowired
    private CatalogStockService catalogStockService;

    @PersistenceContext
    private EntityManager entityManager;

    private final List<UUID> plantedCatalogProductIds = new ArrayList<>();

    /**
     * Planted products are withdrawn rather than deleted - order_items references them
     * with ON DELETE RESTRICT, deliberately, so a past invoice always resolves to a real
     * catalog row. Withdrawing keeps them out of the demo storefront without breaking
     * the orders this class just placed.
     */
    @AfterEach
    void withdrawPlantedCatalogProducts() {
        for (UUID productId : plantedCatalogProductIds) {
            productRepository.findById(productId).ifPresent(product -> {
                product.setMarketplaceListed(false);
                product.setActive(false);
                productRepository.saveAndFlush(product);
            });
        }
        plantedCatalogProductIds.clear();
    }

    // ------------------------------------------------------------------------
    // (a) the cart, and the cross-tenant hop it depends on
    // ------------------------------------------------------------------------

    /**
     * The bug this guards against: cart_items.product_id points at ProcurePal's
     * products while the request runs under the BUYER's Hibernate tenant filter, so a
     * naive repository query matches nothing and the cart comes back silently empty
     * rather than erroring. If BuyerCatalogLookup ever stops loading by primary key,
     * this is what fails.
     */
    @Test
    void aCartLineResolvesThePlatformOwnersCatalogProductAcrossTheTenantBoundary() {
        Buyer buyer = signupBuyer("Cart Crosses Tenants Co");
        Product catalogProduct = plantCatalogProduct(40, 1);

        CartResponse cart = addToCart(buyer, catalogProduct, catalogProduct.getMinOrderQuantity());

        assertThat(cart.items()).hasSize(1);
        assertThat(cart.items().getFirst().productName()).isEqualTo(catalogProduct.getName());
        assertThat(cart.items().getFirst().productSku()).isEqualTo(catalogProduct.getSku());
        // Priced live from the catalog, not from anything stored on the cart line.
        assertThat(cart.items().getFirst().unitPrice()).isEqualByComparingTo(catalogProduct.getUnitPrice());
        assertThat(cart.items().getFirst().available()).isTrue();
        assertThat(cart.subtotal()).isEqualByComparingTo(
                catalogProduct.getUnitPrice().multiply(BigDecimal.valueOf(catalogProduct.getMinOrderQuantity())));
        assertThat(cart.itemCount()).isEqualTo(catalogProduct.getMinOrderQuantity());
    }

    /** A quantity under the product's MOQ is raised to it, not rejected - see CartService. */
    @Test
    void addingBelowTheMinimumOrderQuantityClampsUpToIt() {
        Buyer buyer = signupBuyer("Moq Clamp Co");
        Product product = catalogProducts().stream()
                .filter(candidate -> candidate.getMinOrderQuantity() > 1
                        && catalogStockService.availableToSell(candidate) >= candidate.getMinOrderQuantity())
                .findFirst()
                .orElseThrow(() -> new AssertionError("the seed must contain a listed product with MOQ > 1"));

        CartResponse cart = addToCart(buyer, product, 1);

        assertThat(cart.items().getFirst().quantity()).isEqualTo(product.getMinOrderQuantity());
        assertThat(cart.items().getFirst().minOrderQuantity()).isEqualTo(product.getMinOrderQuantity());
    }

    @Test
    void anUnknownProductCannotBeAddedToTheCart() {
        Buyer buyer = signupBuyer("Unknown Product Co");

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/cart/items",
                HttpMethod.POST,
                new HttpEntity<>(new AddCartItemRequest(UUID.randomUUID(), 1), buyer.headers()),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** A buyer's own product id is not a marketplace product id, however valid it looks. */
    @Test
    void aBuyerCannotAddTheirOwnProductToTheMarketplaceCart() {
        Buyer buyer = signupBuyer("Own Product Co");
        Product own = productRepository.saveAndFlush(ownProductFor(buyer.clientId()));

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/cart/items",
                HttpMethod.POST,
                new HttpEntity<>(new AddCartItemRequest(own.getId(), 1), buyer.headers()),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** The anonymous localStorage cart handed over on login: quantities sum, unknown ids are dropped. */
    @Test
    void mergeSumsIntoExistingLinesAndSkipsProductsThatNoLongerExist() {
        Buyer buyer = signupBuyer("Merge Cart Co");
        Product product = cheapestInStockCatalogProduct();
        int moq = product.getMinOrderQuantity();
        addToCart(buyer, product, moq);

        CartResponse merged = restTemplate.exchange(
                        "/api/cart/merge",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                new MergeCartRequest(List.of(
                                        new AddCartItemRequest(product.getId(), moq),
                                        new AddCartItemRequest(UUID.randomUUID(), 3))),
                                buyer.headers()),
                        CartResponse.class)
                .getBody();

        assertThat(merged).isNotNull();
        assertThat(merged.items()).hasSize(1);
        assertThat(merged.items().getFirst().quantity()).isEqualTo(moq * 2);
    }

    // ------------------------------------------------------------------------
    // (b) checkout, and the pay-on-delivery gates
    // ------------------------------------------------------------------------

    @Test
    void aPrepaidCompanyIsToldWhyItCannotPayOnDelivery() {
        Buyer buyer = signupBuyer("Prepaid Only Co");
        Product product = cheapestInStockCatalogProduct();
        addToCart(buyer, product, product.getMinOrderQuantity());
        createAddress(buyer);

        CheckoutQuoteResponse quote = quote(buyer);

        assertThat(quote.canCheckout()).isTrue();
        assertThat(quote.payOnDeliveryEligible()).isFalse();
        assertThat(quote.payOnDeliveryReasons()).anyMatch(reason -> reason.contains("prepaid"));

        // And the order endpoint enforces it, not just the quote.
        ResponseEntity<ApiError> refused = restTemplate.exchange(
                "/api/orders",
                HttpMethod.POST,
                new HttpEntity<>(
                        new PlaceOrderRequest(PaymentMethod.PAY_ON_DELIVERY, null, null, null, null),
                        buyer.headers()),
                ApiError.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void theQuoteBlocksAnEmptyCartAndSaysSo() {
        Buyer buyer = signupBuyer("Empty Cart Co");
        createAddress(buyer);

        CheckoutQuoteResponse quote = quote(buyer);

        assertThat(quote.canCheckout()).isFalse();
        assertThat(quote.blockers()).anyMatch(blocker -> blocker.contains("empty"));
    }

    // ------------------------------------------------------------------------
    // (c) THE loop: COD checkout -> incoming stock -> fulfilment -> receipt
    // ------------------------------------------------------------------------

    /**
     * The behaviour the whole build exists to produce: "it should show in their
     * inventory but not as what they can use, but as something like pending
     * delivery... after payment is successful."
     *
     * Asserts every step of it - incoming appears with quantity_on_hand still 0 and NO
     * StockMovement, then receipt converts it into on-hand stock WITH a movement priced
     * at what was actually paid.
     */
    @Test
    void aPayOnDeliveryOrderCreatesIncomingStockAndReceivingItTurnsThatIntoOnHandStock() {
        Buyer buyer = signupBuyerAllowedPayOnDelivery("Full Loop Co");
        Product catalogProduct = plantCatalogProduct(40, 1);
        int quantity = catalogProduct.getMinOrderQuantity();
        addToCart(buyer, catalogProduct, quantity);
        DeliveryAddressResponse address = createAddress(buyer);

        OrderResponse order = placeOrder(buyer, PaymentMethod.PAY_ON_DELIVERY, address.id());

        assertThat(order.orderNumber()).matches("PP-\\d{4}-\\d{6}");
        assertThat(order.status()).isEqualTo(OrderStatus.PLACED);
        assertThat(order.paymentStatus()).isEqualTo(PaymentStatus.ON_DELIVERY);
        assertThat(order.delivery().addressLine1()).isEqualTo(address.addressLine1());
        assertThat(order.items()).hasSize(1);
        assertThat(order.events()).isNotEmpty();

        // The cart is emptied by checkout - the basket became the order.
        assertThat(getCart(buyer).items()).isEmpty();

        // Incoming, and ONLY incoming.
        Product buyerProduct = buyerProductFor(buyer, catalogProduct);
        assertThat(buyerProduct.getIncomingQuantity()).isEqualTo(quantity);
        assertThat(buyerProduct.getQuantityOnHand()).isZero();
        assertThat(buyerProduct.isMarketplaceListed()).isFalse();
        assertThat(buyerProduct.getSourceProductId()).isEqualTo(catalogProduct.getId());
        assertThat(stockMovementsFor(buyer)).isZero();

        // ProcurePal fulfils it.
        Buyer operator = loginAsPlatformOwner();
        advance(operator, order.id(), OrderStatus.CONFIRMED);
        advance(operator, order.id(), OrderStatus.PROCESSING);
        advance(operator, order.id(), OrderStatus.OUT_FOR_DELIVERY);
        OrderResponse delivered = advance(operator, order.id(), OrderStatus.DELIVERED);
        assertThat(delivered.deliveredAt()).isNotNull();

        // The buyer signs for it.
        OrderResponse received = receive(buyer, order.id(), null);

        assertThat(received.status()).isEqualTo(OrderStatus.RECEIVED);
        assertThat(received.fullyReceived()).isTrue();
        assertThat(received.receivedAt()).isNotNull();

        Product afterReceipt = productRepository.findById(buyerProduct.getId()).orElseThrow();
        assertThat(afterReceipt.getIncomingQuantity()).isZero();
        assertThat(afterReceipt.getQuantityOnHand()).isEqualTo(quantity);
        assertThat(stockMovementsFor(buyer)).isEqualTo(quantity);
    }

    /** 8 of 10 bags today, 2 tomorrow: the order stays DELIVERED and the remainder stays incoming. */
    @Test
    void aPartialReceiptLeavesTheRemainderIncomingAndTheOrderDelivered() {
        Buyer buyer = signupBuyerAllowedPayOnDelivery("Partial Receipt Co");
        Product catalogProduct = plantCatalogProduct(40, 1);
        int quantity = Math.max(catalogProduct.getMinOrderQuantity(), 2) * 2;
        addToCart(buyer, catalogProduct, quantity);
        DeliveryAddressResponse address = createAddress(buyer);
        OrderResponse order = placeOrder(buyer, PaymentMethod.PAY_ON_DELIVERY, address.id());

        Buyer operator = loginAsPlatformOwner();
        advance(operator, order.id(), OrderStatus.CONFIRMED);
        advance(operator, order.id(), OrderStatus.PROCESSING);
        advance(operator, order.id(), OrderStatus.OUT_FOR_DELIVERY);
        advance(operator, order.id(), OrderStatus.DELIVERED);

        UUID lineId = order.items().getFirst().id();
        int half = quantity / 2;
        OrderResponse partial = receive(
                buyer,
                order.id(),
                new ReceiveOrderRequest(List.of(new ReceiveOrderRequest.ReceiveOrderLine(lineId, half))));

        assertThat(partial.status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(partial.fullyReceived()).isFalse();
        OrderItemResponse line = partial.items().getFirst();
        assertThat(line.receivedQuantity()).isEqualTo(half);
        assertThat(line.outstandingQuantity()).isEqualTo(quantity - half);

        Product buyerProduct = buyerProductFor(buyer, catalogProduct);
        assertThat(buyerProduct.getQuantityOnHand()).isEqualTo(half);
        assertThat(buyerProduct.getIncomingQuantity()).isEqualTo(quantity - half);

        // Finishing it off closes the order.
        OrderResponse complete = receive(buyer, order.id(), null);
        assertThat(complete.status()).isEqualTo(OrderStatus.RECEIVED);
        Product settled = productRepository.findById(buyerProduct.getId()).orElseThrow();
        assertThat(settled.getIncomingQuantity()).isZero();
        assertThat(settled.getQuantityOnHand()).isEqualTo(quantity);
    }

    @Test
    void cancellingAPlacedOrderGivesTheIncomingStockBack() {
        Buyer buyer = signupBuyerAllowedPayOnDelivery("Cancel Reverses Co");
        Product catalogProduct = plantCatalogProduct(40, 1);
        int quantity = catalogProduct.getMinOrderQuantity();
        addToCart(buyer, catalogProduct, quantity);
        DeliveryAddressResponse address = createAddress(buyer);
        OrderResponse order = placeOrder(buyer, PaymentMethod.PAY_ON_DELIVERY, address.id());

        assertThat(buyerProductFor(buyer, catalogProduct).getIncomingQuantity()).isEqualTo(quantity);

        OrderResponse cancelled = restTemplate.exchange(
                        "/api/orders/" + order.id() + "/cancel",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                new com.procurepal_services.stock_bridge_api.order.dto.CancelOrderRequest(
                                        "Ordered by mistake"),
                                buyer.headers()),
                        OrderResponse.class)
                .getBody();

        assertThat(cancelled).isNotNull();
        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.cancellationReason()).isEqualTo("Ordered by mistake");

        Product afterCancel = buyerProductFor(buyer, catalogProduct);
        assertThat(afterCancel.getIncomingQuantity()).isZero();
        // And no phantom stock was created on the way through.
        assertThat(afterCancel.getQuantityOnHand()).isZero();
        assertThat(stockMovementsFor(buyer)).isZero();
    }

    @Test
    void reorderRebuildsTheCartAndReportsWhatItCouldNotAdd() {
        Buyer buyer = signupBuyerAllowedPayOnDelivery("Reorder Co");
        Product catalogProduct = plantCatalogProduct(40, 1);
        addToCart(buyer, catalogProduct, catalogProduct.getMinOrderQuantity());
        DeliveryAddressResponse address = createAddress(buyer);
        OrderResponse order = placeOrder(buyer, PaymentMethod.PAY_ON_DELIVERY, address.id());

        ReorderResponse reordered = restTemplate.exchange(
                        "/api/orders/" + order.id() + "/reorder",
                        HttpMethod.POST,
                        new HttpEntity<>(buyer.headers()),
                        ReorderResponse.class)
                .getBody();

        assertThat(reordered).isNotNull();
        assertThat(reordered.addedCount()).isEqualTo(1);
        assertThat(reordered.skipped()).isEmpty();
        assertThat(reordered.cart().items()).hasSize(1);
        assertThat(reordered.cart().items().getFirst().productId()).isEqualTo(catalogProduct.getId());
    }

    // ------------------------------------------------------------------------
    // (d) payment application: the idempotency property everything else rests on
    // ------------------------------------------------------------------------

    /**
     * A Monnify order gets NO incoming stock at creation - only a verified payment
     * moves it to PLACED - and applying the same verified success twice (a re-delivered
     * webhook, or a webhook racing the return-verify) must move stock exactly once.
     *
     * Deliberately invoked directly on the bean with no TenantContext and no Hibernate
     * filter, which is precisely the situation on the webhook thread. If the
     * implementation ever starts depending on a tenant being set up for it, this fails
     * rather than failing in production at 2am.
     */
    @Test
    void applyingTheSamePaymentSuccessTwiceMovesIncomingStockExactlyOnce() {
        Buyer buyer = signupBuyer("Idempotent Payment Co");
        Product catalogProduct = plantCatalogProduct(40, 1);
        int quantity = catalogProduct.getMinOrderQuantity();
        addToCart(buyer, catalogProduct, quantity);
        DeliveryAddressResponse address = createAddress(buyer);

        OrderResponse order = placeOrder(buyer, PaymentMethod.MONNIFY, address.id());
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.paymentStatus()).isEqualTo(PaymentStatus.PENDING);
        // Nothing paid, nothing incoming.
        assertThat(productRepository.findByClientIdAndSourceProductId(buyer.clientId(), catalogProduct.getId()))
                .isEmpty();

        PaymentSuccess success = new PaymentSuccess(
                "PP-TEST-" + UUID.randomUUID(),
                "MNFY|TEST|" + UUID.randomUUID(),
                order.total(),
                OffsetDateTime.now(),
                "ACCOUNT_TRANSFER",
                PaymentVerificationSource.WEBHOOK);

        orderPaymentApplication.applyPaymentSuccess(order.id(), success);
        orderPaymentApplication.applyPaymentSuccess(order.id(), success);

        OrderResponse afterPayment = getOrder(buyer, order.id());
        assertThat(afterPayment.status()).isEqualTo(OrderStatus.PLACED);
        assertThat(afterPayment.paymentStatus()).isEqualTo(PaymentStatus.PAID);

        Product buyerProduct = buyerProductFor(buyer, catalogProduct);
        assertThat(buyerProduct.getIncomingQuantity())
                .as("a replayed webhook must not double the incoming quantity")
                .isEqualTo(quantity);
        assertThat(buyerProduct.getQuantityOnHand()).isZero();
        assertThat(stockMovementsFor(buyer)).isZero();

        // Exactly one PLACED transition was recorded, not two.
        assertThat(afterPayment.events().stream()
                        .filter(event -> event.toStatus() == OrderStatus.PLACED)
                        .count())
                .isEqualTo(1);
    }

    /** The order module re-checks the amount even though the payment module already did. */
    @Test
    void anUnderpaymentIsRefusedRatherThanMarkingTheOrderPaid() {
        Buyer buyer = signupBuyer("Underpayment Co");
        Product catalogProduct = plantCatalogProduct(40, 1);
        addToCart(buyer, catalogProduct, catalogProduct.getMinOrderQuantity());
        DeliveryAddressResponse address = createAddress(buyer);
        OrderResponse order = placeOrder(buyer, PaymentMethod.MONNIFY, address.id());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> orderPaymentApplication.applyPaymentSuccess(
                        order.id(),
                        new PaymentSuccess(
                                "PP-TEST-" + UUID.randomUUID(),
                                null,
                                order.total().subtract(BigDecimal.ONE),
                                OffsetDateTime.now(),
                                "CARD",
                                PaymentVerificationSource.RETURN_VERIFY)))
                .isInstanceOf(PaymentAmountMismatchException.class);

        assertThat(getOrder(buyer, order.id()).paymentStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    /** Everything the payment module needs to open a checkout, resolvable with no tenant context. */
    @Test
    void thePaymentContextCarriesTheOrderTotalAndTheBuyersContactDetails() {
        Buyer buyer = signupBuyer("Payment Context Co");
        Product catalogProduct = plantCatalogProduct(40, 1);
        addToCart(buyer, catalogProduct, catalogProduct.getMinOrderQuantity());
        DeliveryAddressResponse address = createAddress(buyer);
        OrderResponse order = placeOrder(buyer, PaymentMethod.MONNIFY, address.id());

        OrderPaymentContext context = orderPaymentApplication.loadPaymentContext(order.id());

        assertThat(context.orderId()).isEqualTo(order.id());
        assertThat(context.orderNumber()).isEqualTo(order.orderNumber());
        assertThat(context.total()).isEqualByComparingTo(order.total());
        assertThat(context.currency()).isEqualTo("NGN");
        assertThat(context.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(context.paymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(context.customerEmail()).isNotBlank();
        assertThat(context.customerName()).isNotBlank();
    }

    // ------------------------------------------------------------------------
    // (e) isolation
    // ------------------------------------------------------------------------

    @Test
    void oneCompanyCannotReadAnotherCompanysOrder() {
        Buyer owner = signupBuyerAllowedPayOnDelivery("Order Owner Co");
        Buyer intruder = signupBuyer("Order Intruder Co");
        Product catalogProduct = plantCatalogProduct(40, 1);
        addToCart(owner, catalogProduct, catalogProduct.getMinOrderQuantity());
        DeliveryAddressResponse address = createAddress(owner);
        OrderResponse order = placeOrder(owner, PaymentMethod.PAY_ON_DELIVERY, address.id());

        ResponseEntity<ApiError> read = restTemplate.exchange(
                "/api/orders/" + order.id(),
                HttpMethod.GET,
                new HttpEntity<>(intruder.headers()),
                ApiError.class);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<ApiError> cancel = restTemplate.exchange(
                "/api/orders/" + order.id() + "/cancel",
                HttpMethod.POST,
                new HttpEntity<>(
                        new com.procurepal_services.stock_bridge_api.order.dto.CancelOrderRequest("not mine"),
                        intruder.headers()),
                ApiError.class);
        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // And the intruder's own list does not contain it.
        assertThat(listOrders(intruder).stream().map(OrderSummaryResponse::id)).doesNotContain(order.id());
        assertThat(listOrders(owner).stream().map(OrderSummaryResponse::id)).contains(order.id());
    }

    /**
     * orders.client_id is the BUYER, so ProcurePal's queue only works because it runs
     * inside PlatformOwnerGuard.readAcrossTenants. Without it this returns an empty
     * page that looks exactly like "no orders today" - which is why it is asserted
     * here rather than left to be noticed in production.
     */
    @Test
    void theFulfilmentQueueReturnsOtherTenantsOrders() {
        Buyer buyer = signupBuyerAllowedPayOnDelivery("Queue Visible Co");
        Product catalogProduct = plantCatalogProduct(40, 1);
        addToCart(buyer, catalogProduct, catalogProduct.getMinOrderQuantity());
        DeliveryAddressResponse address = createAddress(buyer);
        OrderResponse order = placeOrder(buyer, PaymentMethod.PAY_ON_DELIVERY, address.id());

        Buyer operator = loginAsPlatformOwner();
        List<OrderSummaryResponse> queue = restTemplate.exchange(
                        "/api/marketplace/admin/orders?q=" + order.orderNumber(),
                        HttpMethod.GET,
                        new HttpEntity<>(operator.headers()),
                        new ParameterizedTypeReference<TestPage<OrderSummaryResponse>>() {})
                .getBody()
                .content();

        assertThat(queue).extracting(OrderSummaryResponse::id).contains(order.id());
        OrderSummaryResponse row = queue.stream()
                .filter(candidate -> candidate.id().equals(order.id()))
                .findFirst()
                .orElseThrow();
        // The buyer is named on ProcurePal's side and absent on the buyer's own.
        assertThat(row.customer()).isNotNull();
        assertThat(row.customer().clientId()).isEqualTo(buyer.clientId());

        OrderResponse detail = restTemplate.exchange(
                        "/api/marketplace/admin/orders/" + order.id(),
                        HttpMethod.GET,
                        new HttpEntity<>(operator.headers()),
                        OrderResponse.class)
                .getBody();
        assertThat(detail).isNotNull();
        assertThat(detail.customer()).isNotNull();
        assertThat(detail.items()).isNotEmpty();
    }

    /**
     * MANAGE_MARKETPLACE_ORDERS is held by EVERY tenant's OWNER - permissions hang off
     * global roles - so @PreAuthorize alone would let this caller straight through. The
     * 403 can only come from the platform-owner gate.
     */
    @Test
    void anOrdinaryTenantsOwnerIsRefusedOnEveryFulfilmentRoute() {
        Buyer outsider = signupBuyer("Not The Operator Co");
        assertThat(outsider.login().user().permissions()).contains("MANAGE_MARKETPLACE_ORDERS");

        assertForbidden(HttpMethod.GET, "/api/marketplace/admin/orders", null, outsider);
        assertForbidden(HttpMethod.GET, "/api/marketplace/admin/orders/" + UUID.randomUUID(), null, outsider);
        assertForbidden(
                HttpMethod.POST,
                "/api/marketplace/admin/orders/" + UUID.randomUUID() + "/status",
                new AdvanceOrderStatusRequest(OrderStatus.CONFIRMED, null),
                outsider);
        assertForbidden(
                HttpMethod.POST,
                "/api/marketplace/admin/orders/" + UUID.randomUUID() + "/payment-received",
                null,
                outsider);
        assertForbidden(HttpMethod.GET, "/api/marketplace/admin/customers", null, outsider);
    }

    // ------------------------------------------------------------------------
    // (f) the fulfilment side's own rules
    // ------------------------------------------------------------------------

    /** DELIVERED -> RECEIVED is the buyer's to make: it writes stock into their inventory. */
    @Test
    void procurePalCannotMarkAnOrderReceivedOnTheBuyersBehalf() {
        Buyer buyer = signupBuyerAllowedPayOnDelivery("Buyer Driven Co");
        Product catalogProduct = plantCatalogProduct(40, 1);
        addToCart(buyer, catalogProduct, catalogProduct.getMinOrderQuantity());
        DeliveryAddressResponse address = createAddress(buyer);
        OrderResponse order = placeOrder(buyer, PaymentMethod.PAY_ON_DELIVERY, address.id());

        Buyer operator = loginAsPlatformOwner();
        advance(operator, order.id(), OrderStatus.CONFIRMED);
        advance(operator, order.id(), OrderStatus.PROCESSING);
        advance(operator, order.id(), OrderStatus.OUT_FOR_DELIVERY);
        advance(operator, order.id(), OrderStatus.DELIVERED);

        ResponseEntity<ApiError> refused = restTemplate.exchange(
                "/api/marketplace/admin/orders/" + order.id() + "/status",
                HttpMethod.POST,
                new HttpEntity<>(new AdvanceOrderStatusRequest(OrderStatus.RECEIVED, null), operator.headers()),
                ApiError.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(orderRepository.findById(order.id()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.DELIVERED);
    }

    /** The state machine on OrderStatus is the only definition; skipping a step is a 409. */
    @Test
    void anIllegalStatusJumpIsRefused() {
        Buyer buyer = signupBuyerAllowedPayOnDelivery("Illegal Jump Co");
        Product catalogProduct = plantCatalogProduct(40, 1);
        addToCart(buyer, catalogProduct, catalogProduct.getMinOrderQuantity());
        DeliveryAddressResponse address = createAddress(buyer);
        OrderResponse order = placeOrder(buyer, PaymentMethod.PAY_ON_DELIVERY, address.id());

        Buyer operator = loginAsPlatformOwner();
        ResponseEntity<ApiError> refused = restTemplate.exchange(
                "/api/marketplace/admin/orders/" + order.id() + "/status",
                HttpMethod.POST,
                new HttpEntity<>(
                        new AdvanceOrderStatusRequest(OrderStatus.OUT_FOR_DELIVERY, null), operator.headers()),
                ApiError.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    /** Settling a COD order twice must not double-count anything. */
    @Test
    void recordingPaymentReceivedOnACodOrderIsIdempotent() {
        Buyer buyer = signupBuyerAllowedPayOnDelivery("Cod Settle Co");
        Product catalogProduct = plantCatalogProduct(40, 1);
        addToCart(buyer, catalogProduct, catalogProduct.getMinOrderQuantity());
        DeliveryAddressResponse address = createAddress(buyer);
        OrderResponse order = placeOrder(buyer, PaymentMethod.PAY_ON_DELIVERY, address.id());

        Buyer operator = loginAsPlatformOwner();
        OrderResponse first = settleCod(operator, order.id());
        OrderResponse second = settleCod(operator, order.id());

        assertThat(first.paymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(second.paymentStatus()).isEqualTo(PaymentStatus.PAID);
        // Fulfilment is untouched: the two axes move independently.
        assertThat(second.status()).isEqualTo(OrderStatus.PLACED);
        assertThat(buyerProductFor(buyer, catalogProduct).getIncomingQuantity())
                .isEqualTo(catalogProduct.getMinOrderQuantity());
    }

    @Test
    void theCustomersListNamesBuyersAndExcludesProcurePalItself() {
        Buyer buyer = signupBuyerAllowedPayOnDelivery("Customer List Co");
        Buyer operator = loginAsPlatformOwner();
        // Searched by slug rather than name: the signup name carries a random suffix,
        // and a query string with spaces is a RestTemplate encoding problem, not a
        // property of the endpoint worth testing here.
        String slug = clientRepository.findById(buyer.clientId()).orElseThrow().getSlug();

        List<com.procurepal_services.stock_bridge_api.marketplace.dto.MarketplaceCustomerResponse> customers =
                restTemplate.exchange(
                                "/api/marketplace/admin/customers?q=" + slug,
                                HttpMethod.GET,
                                new HttpEntity<>(operator.headers()),
                                new ParameterizedTypeReference<TestPage<
                                        com.procurepal_services.stock_bridge_api.marketplace.dto
                                                .MarketplaceCustomerResponse>>() {})
                        .getBody()
                        .content();

        assertThat(customers).isNotEmpty();
        assertThat(customers).allSatisfy(customer ->
                assertThat(customer.clientId()).isNotEqualTo(platformOwner().getId()));
        assertThat(customers).anyMatch(customer -> customer.clientId().equals(buyer.clientId()));
    }

    // ------------------------------------------------------------------------
    // (g) ProcurePal's OWN stock - the seller side of the same goods
    // ------------------------------------------------------------------------

    /**
     * ProcurePal runs its own inventory in this app, so selling has to move its stock.
     * The deduction happens at dispatch, not at order: that is when the goods physically
     * leave, and it is a transition reachable exactly once.
     */
    @Test
    void dispatchingAnOrderWritesAnOutMovementAndReducesProcurePalsOwnStock() {
        Buyer buyer = signupBuyerAllowedPayOnDelivery("Seller Stock Co");
        Product catalogProduct = plantCatalogProduct(40, 1);
        addToCart(buyer, catalogProduct, 4);
        DeliveryAddressResponse address = createAddress(buyer);
        OrderResponse order = placeOrder(buyer, PaymentMethod.PAY_ON_DELIVERY, address.id());

        // Placing an order moves nothing: the bags are still on ProcurePal's shelf.
        assertThat(catalogOnHand(catalogProduct.getId())).isEqualTo(40);
        assertThat(outMovementsFor(catalogProduct.getId())).isZero();

        Buyer operator = loginAsPlatformOwner();
        advance(operator, order.id(), OrderStatus.CONFIRMED);
        advance(operator, order.id(), OrderStatus.PROCESSING);
        assertThat(catalogOnHand(catalogProduct.getId()))
                .as("nothing leaves the warehouse until it is out for delivery")
                .isEqualTo(40);

        advance(operator, order.id(), OrderStatus.OUT_FOR_DELIVERY);

        assertThat(catalogOnHand(catalogProduct.getId())).isEqualTo(36);
        assertThat(outMovementsFor(catalogProduct.getId())).isEqualTo(1);
    }

    /**
     * Same standard as the buyer-side incoming stock: the transition is guarded by the
     * state machine and by a pessimistic lock on the order row, so a second dispatch
     * cannot write a second set of movements.
     */
    @Test
    void dispatchingTwiceMovesProcurePalsStockExactlyOnce() {
        Buyer buyer = signupBuyerAllowedPayOnDelivery("Double Dispatch Co");
        Product catalogProduct = plantCatalogProduct(40, 1);
        addToCart(buyer, catalogProduct, 4);
        DeliveryAddressResponse address = createAddress(buyer);
        OrderResponse order = placeOrder(buyer, PaymentMethod.PAY_ON_DELIVERY, address.id());

        Buyer operator = loginAsPlatformOwner();
        advance(operator, order.id(), OrderStatus.CONFIRMED);
        advance(operator, order.id(), OrderStatus.PROCESSING);
        advance(operator, order.id(), OrderStatus.OUT_FOR_DELIVERY);

        ResponseEntity<ApiError> secondDispatch = restTemplate.exchange(
                "/api/marketplace/admin/orders/" + order.id() + "/status",
                HttpMethod.POST,
                new HttpEntity<>(
                        new AdvanceOrderStatusRequest(OrderStatus.OUT_FOR_DELIVERY, "resent"), operator.headers()),
                ApiError.class);

        assertThat(secondDispatch.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(catalogOnHand(catalogProduct.getId())).isEqualTo(36);
        assertThat(outMovementsFor(catalogProduct.getId()))
                .as("a re-sent dispatch must not write a second ledger row")
                .isEqualTo(1);
    }

    /**
     * The gap that deducting at dispatch would otherwise leave: two companies each
     * buying the last ten bags on the same afternoon and both being told yes.
     * Availability is on-hand minus everything already sold and not yet dispatched, and
     * order creation re-checks it with the catalog row locked.
     */
    @Test
    void aSecondCompanyCannotBuyStockThatIsAlreadySoldButNotYetDispatched() {
        Product catalogProduct = plantCatalogProduct(10, 1);

        Buyer second = signupBuyer("Second Buyer Co");
        Buyer first = signupBuyerAllowedPayOnDelivery("First Buyer Co");

        // Both fill a cart while all ten are still free - a cart commits nothing.
        addToCart(second, catalogProduct, 10);
        addToCart(first, catalogProduct, 10);
        createAddress(second);
        DeliveryAddressResponse firstAddress = createAddress(first);

        placeOrder(first, PaymentMethod.PAY_ON_DELIVERY, firstAddress.id());
        assertThat(catalogOnHand(catalogProduct.getId()))
                .as("still on the shelf, but no longer sellable")
                .isEqualTo(10);

        ResponseEntity<ApiError> oversell = restTemplate.exchange(
                "/api/orders",
                HttpMethod.POST,
                new HttpEntity<>(
                        new PlaceOrderRequest(PaymentMethod.MONNIFY, null, null, null, null), second.headers()),
                ApiError.class);

        assertThat(oversell.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(orderRepository.countByClientId(second.clientId())).isZero();

        // And the quote says so up front rather than letting them reach a dead end.
        CheckoutQuoteResponse quote = quote(second);
        assertThat(quote.canCheckout()).isFalse();
        assertThat(quote.unavailableItems()).isNotEmpty();
        assertThat(quote.unavailableItems().getFirst().reason()).contains("Out of stock");
    }

    /**
     * Confirms the design claim in CatalogStockService: because CANCELLED is unreachable
     * from OUT_FOR_DELIVERY onwards, a cancelled order is by construction one whose goods
     * never moved - so there is nothing to reverse on the seller's side, and the stock
     * frees itself simply by ceasing to count as committed.
     */
    @Test
    void cancellingBeforeDispatchLeavesProcurePalsStockUntouchedAndReleasesIt() {
        Buyer buyer = signupBuyerAllowedPayOnDelivery("Cancel Before Dispatch Co");
        Product catalogProduct = plantCatalogProduct(10, 1);
        addToCart(buyer, catalogProduct, 10);
        DeliveryAddressResponse address = createAddress(buyer);
        OrderResponse order = placeOrder(buyer, PaymentMethod.PAY_ON_DELIVERY, address.id());

        restTemplate.exchange(
                "/api/orders/" + order.id() + "/cancel",
                HttpMethod.POST,
                new HttpEntity<>(
                        new com.procurepal_services.stock_bridge_api.order.dto.CancelOrderRequest("Changed our mind"),
                        buyer.headers()),
                OrderResponse.class);

        assertThat(catalogOnHand(catalogProduct.getId())).isEqualTo(10);
        assertThat(outMovementsFor(catalogProduct.getId()))
                .as("no goods moved, so no ledger row - and therefore nothing to reverse")
                .isZero();

        // The ten bags are sellable again, with no compensating write anywhere.
        Buyer nextBuyer = signupBuyer("Next In Line Co");
        CartResponse cart = addToCart(nextBuyer, catalogProduct, 10);
        assertThat(cart.items().getFirst().available()).isTrue();
        assertThat(cart.items().getFirst().quantityOnHand()).isEqualTo(10);
    }

    // ------------------------------------------------------------------------
    // (h) delivery addresses
    // ------------------------------------------------------------------------

    @Test
    void theFirstAddressBecomesTheDefaultAndPromotingAnotherSwapsIt() {
        Buyer buyer = signupBuyer("Address Default Co");

        DeliveryAddressResponse first = createAddress(buyer);
        assertThat(first.isDefault()).isTrue();

        DeliveryAddressResponse second = restTemplate.exchange(
                        "/api/delivery-addresses",
                        HttpMethod.POST,
                        new HttpEntity<>(addressRequest("Warehouse 2"), buyer.headers()),
                        DeliveryAddressResponse.class)
                .getBody();
        assertThat(second).isNotNull();
        assertThat(second.isDefault()).isFalse();

        DeliveryAddressResponse promoted = restTemplate.exchange(
                        "/api/delivery-addresses/" + second.id() + "/default",
                        HttpMethod.POST,
                        new HttpEntity<>(buyer.headers()),
                        DeliveryAddressResponse.class)
                .getBody();
        assertThat(promoted).isNotNull();
        assertThat(promoted.isDefault()).isTrue();

        List<DeliveryAddressResponse> all = listAddresses(buyer);
        assertThat(all.stream().filter(DeliveryAddressResponse::isDefault)).hasSize(1);
        assertThat(all.getFirst().id()).isEqualTo(second.id());
    }

    @Test
    void aStateOutsideNigeriaIsRejected() {
        Buyer buyer = signupBuyer("Bad State Co");

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/delivery-addresses",
                HttpMethod.POST,
                new HttpEntity<>(
                        new DeliveryAddressRequest(
                                "Head Office",
                                "Ada Okafor",
                                "+234 801 234 5678",
                                "14 Adeola Odeku Street",
                                null,
                                "Victoria Island",
                                "Californ-I-A",
                                null,
                                null,
                                null,
                                null),
                        buyer.headers()),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("Nigeria");
    }

    /** Deleting is a deactivation, and the default moves rather than disappearing. */
    @Test
    void deletingTheDefaultAddressPromotesAnother() {
        Buyer buyer = signupBuyer("Address Delete Co");
        DeliveryAddressResponse first = createAddress(buyer);
        DeliveryAddressResponse second = restTemplate.exchange(
                        "/api/delivery-addresses",
                        HttpMethod.POST,
                        new HttpEntity<>(addressRequest("Second Site"), buyer.headers()),
                        DeliveryAddressResponse.class)
                .getBody();

        restTemplate.exchange(
                "/api/delivery-addresses/" + first.id(),
                HttpMethod.DELETE,
                new HttpEntity<>(buyer.headers()),
                Void.class);

        List<DeliveryAddressResponse> remaining = listAddresses(buyer);
        assertThat(remaining).extracting(DeliveryAddressResponse::id).containsExactly(second.id());
        assertThat(remaining.getFirst().isDefault()).isTrue();
    }

    // ------------------------------------------------------------------------
    // (i) notifications
    // ------------------------------------------------------------------------

    @Test
    void placingAnOrderNotifiesProcurePalAndAdvancingItNotifiesTheBuyer() {
        Buyer buyer = signupBuyerAllowedPayOnDelivery("Notified Co");
        Product catalogProduct = plantCatalogProduct(40, 1);
        addToCart(buyer, catalogProduct, catalogProduct.getMinOrderQuantity());
        DeliveryAddressResponse address = createAddress(buyer);
        OrderResponse order = placeOrder(buyer, PaymentMethod.PAY_ON_DELIVERY, address.id());

        Buyer operator = loginAsPlatformOwner();
        assertThat(notificationsOf(operator))
                .anyMatch(notification -> "NEW_ORDER".equals(notification.type())
                        && notification.title().contains(order.orderNumber()));

        advance(operator, order.id(), OrderStatus.CONFIRMED);

        var buyerNotifications = notificationsOf(buyer);
        assertThat(buyerNotifications)
                .anyMatch(notification -> "ORDER_STATUS_CHANGED".equals(notification.type())
                        && notification.title().contains(order.orderNumber()));
        // Tenant-scoped: the buyer never sees ProcurePal's copy.
        assertThat(buyerNotifications).noneMatch(notification -> "NEW_ORDER".equals(notification.type()));

        UUID notificationId = buyerNotifications.getFirst().id();
        restTemplate.exchange(
                "/api/notifications/" + notificationId + "/read",
                HttpMethod.POST,
                new HttpEntity<>(buyer.headers()),
                TestNotification.class);

        assertThat(unreadNotificationsOf(buyer)).noneMatch(n -> n.id().equals(notificationId));
    }

    // ------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------

    private record Buyer(TenantLoginResponse login, UUID clientId) {

        HttpHeaders headers() {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(login.tokens().accessToken());
            return headers;
        }
    }

    /** Spring's Page JSON has no public constructor Jackson can use; only content is asserted on. */
    private record TestPage<T>(List<T> content) {
    }

    private record TestNotification(UUID id, String type, String title, String body, String link, UUID orderId) {
    }

    private Buyer signupBuyer(String name) {
        String unique = UUID.randomUUID().toString();
        ClientSignupRequest request = new ClientSignupRequest(
                name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD);
        TenantLoginResponse login = restTemplate.postForObject("/api/clients/signup", request, TenantLoginResponse.class);
        assertThat(login).isNotNull();
        UUID clientId = clientRepository.findBySlug(login.user().clientIdentifier()).orElseThrow().getId();
        return new Buyer(login, clientId);
    }

    /**
     * Pay-on-delivery has three independent gates; a new signup is PREPAID by default,
     * so the commercial relationship is flipped directly on the client row - that is
     * ProcurePal ops' decision, and there is no self-service endpoint for it by design.
     */
    private Buyer signupBuyerAllowedPayOnDelivery(String name) {
        Buyer buyer = signupBuyer(name);
        Client client = clientRepository.findById(buyer.clientId()).orElseThrow();
        client.setPaymentTerms(PaymentTerms.PAY_ON_DELIVERY_ALLOWED);
        clientRepository.saveAndFlush(client);
        return buyer;
    }

    private Buyer loginAsPlatformOwner() {
        TenantLoginResponse login = restTemplate.postForObject(
                "/api/auth/login", new LoginRequest("procurepal", "admin", "Demo1234!"), TenantLoginResponse.class);
        assertThat(login)
                .as("the procurepal demo tenant must be seeded - see db/seed/V9001__seed_procurepal_marketplace.sql")
                .isNotNull();
        return new Buyer(login, platformOwner().getId());
    }

    private Client platformOwner() {
        return clientRepository.findByPlatformOwnerTrue().orElseThrow();
    }

    private List<Product> catalogProducts() {
        return productRepository
                .findAllByClientIdAndMarketplaceListedTrueAndActiveTrue(platformOwner().getId(), PageRequest.of(0, 200))
                .getContent();
    }

    /**
     * Cheapest SELLABLE listed product from the real seeded catalog, for the cart tests
     * that deliberately exercise the seed rather than a planted fixture.
     *
     * Filtered on availableToSell, not on quantity_on_hand: orders left at PLACED by
     * earlier runs of this suite still count as committed against the product they
     * bought, and a filter on the raw column would eventually keep choosing a product
     * that can no longer be sold.
     */
    private Product cheapestInStockCatalogProduct() {
        return catalogProducts().stream()
                .filter(product ->
                        catalogStockService.availableToSell(product) >= product.getMinOrderQuantity() * 4)
                .min(Comparator.comparing(Product::getUnitPrice))
                .orElseThrow(() -> new AssertionError("the seed must contain a sellable listed product"));
    }

    private Product ownProductFor(UUID clientId) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        Product product = Product.builder()
                .name("Own Item " + unique)
                .sku("OWN-" + unique)
                .unitPrice(new BigDecimal("1000.00"))
                .quantityOnHand(10)
                .active(true)
                .marketplaceListed(false)
                .build();
        // client_id comes from TenantContext on persist, so it has to be set here the
        // way a privileged server-side flow would (see TenantAwareEntity).
        TenantContext.set(clientId);
        try {
            return productRepository.saveAndFlush(product);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * A listed ProcurePal product created just for one test.
     *
     * Not the seeded catalog, because availability is on-hand MINUS everything sold and
     * not yet dispatched: every COD order these tests leave sitting at PLACED counts
     * against the product it bought, forever, and the local Postgres is never reset. On
     * the shared seed that would quietly drain one product until the whole class started
     * failing a few hundred runs from now. A fresh product per test also makes the stock
     * assertions exact rather than relative.
     */
    private Product plantCatalogProduct(int quantityOnHand, int minOrderQuantity) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        Product product = Product.builder()
                .name("Test Catalog Item " + unique)
                .sku("PP-TEST-" + unique)
                .slug("pp-test-" + unique)
                .description("Planted by MarketplaceOrderIntegrationTest.")
                .unitPrice(new BigDecimal("12000.00"))
                .quantityOnHand(quantityOnHand)
                .active(true)
                .marketplaceListed(true)
                .unitOfMeasure("bag (25kg)")
                .minOrderQuantity(minOrderQuantity)
                .build();
        TenantContext.set(platformOwner().getId());
        try {
            Product saved = productRepository.saveAndFlush(product);
            plantedCatalogProductIds.add(saved.getId());
            return saved;
        } finally {
            TenantContext.clear();
        }
    }

    private int catalogOnHand(UUID catalogProductId) {
        return productRepository.findById(catalogProductId).orElseThrow().getQuantityOnHand();
    }

    /** Counts ledger rows for ONE product, so a neighbouring test's movements cannot be miscounted. */
    private long outMovementsFor(UUID productId) {
        return (Long) entityManager
                .createQuery("SELECT COUNT(m) FROM StockMovement m "
                        + "WHERE m.product.id = :productId AND m.movementType = :type")
                .setParameter("productId", productId)
                .setParameter("type", MovementType.OUT)
                .getSingleResult();
    }

    private Product buyerProductFor(Buyer buyer, Product catalogProduct) {
        return productRepository
                .findByClientIdAndSourceProductId(buyer.clientId(), catalogProduct.getId())
                .orElseThrow(() -> new AssertionError("the buyer should have an inventory row for this purchase"));
    }

    private long stockMovementsFor(Buyer buyer) {
        return stockMovementRepository.sumQuantity(
                buyer.clientId(),
                MovementType.IN,
                OffsetDateTime.now().minusDays(1),
                OffsetDateTime.now().plusDays(1));
    }

    private CartResponse addToCart(Buyer buyer, Product product, int quantity) {
        // Status asserted before the body is read: a refused add returns an ApiError,
        // which would otherwise surface as an unreadable-JSON error naming a primitive
        // field rather than the reason the add was rejected.
        ResponseEntity<String> raw = restTemplate.exchange(
                "/api/cart/items",
                HttpMethod.POST,
                new HttpEntity<>(new AddCartItemRequest(product.getId(), quantity), buyer.headers()),
                String.class);
        assertThat(raw.getStatusCode()).as("add %s x%d to cart: %s", product.getSku(), quantity, raw.getBody())
                .isEqualTo(HttpStatus.OK);
        return getCart(buyer);
    }

    private CartResponse getCart(Buyer buyer) {
        return restTemplate
                .exchange("/api/cart", HttpMethod.GET, new HttpEntity<>(buyer.headers()), CartResponse.class)
                .getBody();
    }

    private DeliveryAddressRequest addressRequest(String label) {
        return new DeliveryAddressRequest(
                label,
                "Ada Okafor",
                "+234 801 234 5678",
                "14 Adeola Odeku Street",
                "Second floor",
                "Victoria Island",
                "Lagos",
                "Opposite Eko Hotel roundabout",
                "Deliveries 8am - 4pm weekdays.",
                null,
                null);
    }

    private DeliveryAddressResponse createAddress(Buyer buyer) {
        DeliveryAddressResponse address = restTemplate.exchange(
                        "/api/delivery-addresses",
                        HttpMethod.POST,
                        new HttpEntity<>(addressRequest("Main Kitchen"), buyer.headers()),
                        DeliveryAddressResponse.class)
                .getBody();
        assertThat(address).isNotNull();
        return address;
    }

    private List<DeliveryAddressResponse> listAddresses(Buyer buyer) {
        return restTemplate
                .exchange(
                        "/api/delivery-addresses",
                        HttpMethod.GET,
                        new HttpEntity<>(buyer.headers()),
                        new ParameterizedTypeReference<List<DeliveryAddressResponse>>() {})
                .getBody();
    }

    private CheckoutQuoteResponse quote(Buyer buyer) {
        CheckoutQuoteResponse quote = restTemplate.exchange(
                        "/api/checkout/quote",
                        HttpMethod.POST,
                        new HttpEntity<>(new CheckoutQuoteRequest(null), buyer.headers()),
                        CheckoutQuoteResponse.class)
                .getBody();
        assertThat(quote).isNotNull();
        return quote;
    }

    private OrderResponse placeOrder(Buyer buyer, PaymentMethod method, UUID addressId) {
        ResponseEntity<OrderResponse> response = restTemplate.exchange(
                "/api/orders",
                HttpMethod.POST,
                new HttpEntity<>(new PlaceOrderRequest(method, addressId, null, null, "Ring the bell"), buyer.headers()),
                OrderResponse.class);
        assertThat(response.getStatusCode()).as("place order").isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private OrderResponse getOrder(Buyer buyer, UUID orderId) {
        return restTemplate
                .exchange(
                        "/api/orders/" + orderId,
                        HttpMethod.GET,
                        new HttpEntity<>(buyer.headers()),
                        OrderResponse.class)
                .getBody();
    }

    private List<OrderSummaryResponse> listOrders(Buyer buyer) {
        return restTemplate
                .exchange(
                        "/api/orders?size=100",
                        HttpMethod.GET,
                        new HttpEntity<>(buyer.headers()),
                        new ParameterizedTypeReference<TestPage<OrderSummaryResponse>>() {})
                .getBody()
                .content();
    }

    private OrderResponse advance(Buyer operator, UUID orderId, OrderStatus status) {
        ResponseEntity<OrderResponse> response = restTemplate.exchange(
                "/api/marketplace/admin/orders/" + orderId + "/status",
                HttpMethod.POST,
                new HttpEntity<>(new AdvanceOrderStatusRequest(status, "Moved to " + status), operator.headers()),
                OrderResponse.class);
        assertThat(response.getStatusCode()).as("advance to %s", status).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private OrderResponse settleCod(Buyer operator, UUID orderId) {
        return restTemplate
                .exchange(
                        "/api/marketplace/admin/orders/" + orderId + "/payment-received",
                        HttpMethod.POST,
                        new HttpEntity<>(operator.headers()),
                        OrderResponse.class)
                .getBody();
    }

    private OrderResponse receive(Buyer buyer, UUID orderId, ReceiveOrderRequest request) {
        ResponseEntity<OrderResponse> response = restTemplate.exchange(
                "/api/orders/" + orderId + "/receive",
                HttpMethod.POST,
                new HttpEntity<>(request, buyer.headers()),
                OrderResponse.class);
        assertThat(response.getStatusCode()).as("receive").isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private List<TestNotification> notificationsOf(Buyer buyer) {
        return restTemplate
                .exchange(
                        "/api/notifications?size=50",
                        HttpMethod.GET,
                        new HttpEntity<>(buyer.headers()),
                        new ParameterizedTypeReference<TestPage<TestNotification>>() {})
                .getBody()
                .content();
    }

    private List<TestNotification> unreadNotificationsOf(Buyer buyer) {
        return restTemplate
                .exchange(
                        "/api/notifications?unreadOnly=true&size=50",
                        HttpMethod.GET,
                        new HttpEntity<>(buyer.headers()),
                        new ParameterizedTypeReference<TestPage<TestNotification>>() {})
                .getBody()
                .content();
    }

    private void assertForbidden(HttpMethod method, String path, Object body, Buyer caller) {
        ResponseEntity<ApiError> response = restTemplate.exchange(
                path, method, new HttpEntity<>(body, caller.headers()), ApiError.class);
        assertThat(response.getStatusCode()).as("%s %s", method, path).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
