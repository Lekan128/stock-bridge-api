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
import com.procurepal_services.stock_bridge_api.entity.ClientType;
import com.procurepal_services.stock_bridge_api.entity.MovementType;
import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.OrderItem;
import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.PaymentMethod;
import com.procurepal_services.stock_bridge_api.entity.PaymentStatus;
import com.procurepal_services.stock_bridge_api.entity.PaymentTerms;
import com.procurepal_services.stock_bridge_api.entity.PaymentVerificationSource;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.entity.ProductApprovalStatus;
import com.procurepal_services.stock_bridge_api.entity.ProductVendor;
import com.procurepal_services.stock_bridge_api.entity.ProductVendorPack;
import com.procurepal_services.stock_bridge_api.entity.SkuResetCadence;
import com.procurepal_services.stock_bridge_api.marketplace.dto.AdvanceOrderStatusRequest;
import com.procurepal_services.stock_bridge_api.order.dto.CheckoutQuoteRequest;
import com.procurepal_services.stock_bridge_api.order.dto.CheckoutQuoteResponse;
import com.procurepal_services.stock_bridge_api.order.dto.OrderItemMatchSuggestionResponse;
import com.procurepal_services.stock_bridge_api.order.dto.OrderItemResponse;
import com.procurepal_services.stock_bridge_api.order.dto.OrderResponse;
import com.procurepal_services.stock_bridge_api.order.dto.OrderSummaryResponse;
import com.procurepal_services.stock_bridge_api.order.dto.PlaceOrderRequest;
import com.procurepal_services.stock_bridge_api.order.dto.ReceiveOrderRequest;
import com.procurepal_services.stock_bridge_api.order.dto.ReorderResponse;
import com.procurepal_services.stock_bridge_api.product.sku.dto.UpdateProductSkuSettingsRequest;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.OrderRepository;
import com.procurepal_services.stock_bridge_api.repository.OrderItemRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductVendorPackRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductVendorRepository;
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
    private OrderItemRepository orderItemRepository;

    @Autowired
    private ProductVendorRepository productVendorRepository;

    @Autowired
    private ProductVendorPackRepository productVendorPackRepository;

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

        // The seller's own code for this item lands on the ProductVendor pairing's default
        // pack - never on the buyer's own Product.sku, which this receipt must not touch.
        assertThat(defaultPackVendorSku(buyer, afterReceipt.getId())).isEqualTo(catalogProduct.getSku());
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
                new ReceiveOrderRequest(List.of(new ReceiveOrderRequest.ReceiveOrderLine(lineId, half, null))));

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

    /**
     * A buyer with SKU auto-generation turned on gets a marketplace purchase's first-ever product
     * numbered under their OWN scheme, exactly as ProductManagementService.create() would number
     * a product they typed in by hand - never the seller's raw catalog SKU. The seller's code is
     * not lost, though: it lands as the "supplier's code" on the resulting vendor pairing, which
     * is where every other vendor's own code for an item already lives.
     */
    @Test
    void aBuyersOwnSkuSchemeNumbersAFreshMarketplacePurchaseAndTheSellersCodeSurvivesAsTheVendorsSku() {
        Buyer buyer = signupBuyerAllowedPayOnDelivery("Own Sku Scheme Co");
        enableSkuGeneration(buyer, "OSC-{SEQ:4}");
        Product catalogProduct = plantCatalogProduct(40, 1);
        int quantity = catalogProduct.getMinOrderQuantity();
        addToCart(buyer, catalogProduct, quantity);
        DeliveryAddressResponse address = createAddress(buyer);
        OrderResponse order = placeOrder(buyer, PaymentMethod.PAY_ON_DELIVERY, address.id());

        Product buyerProduct = buyerProductFor(buyer, catalogProduct);
        assertThat(buyerProduct.getSku()).isNotEqualTo(catalogProduct.getSku());
        assertThat(buyerProduct.getSku()).startsWith("OSC-");

        Buyer operator = loginAsPlatformOwner();
        advance(operator, order.id(), OrderStatus.CONFIRMED);
        advance(operator, order.id(), OrderStatus.PROCESSING);
        advance(operator, order.id(), OrderStatus.OUT_FOR_DELIVERY);
        advance(operator, order.id(), OrderStatus.DELIVERED);
        receive(buyer, order.id(), null);

        // Own SKU stays exactly what was generated at PLACED - a receipt never touches it.
        Product afterReceipt = productRepository.findById(buyerProduct.getId()).orElseThrow();
        assertThat(afterReceipt.getSku()).isEqualTo(buyerProduct.getSku());
        assertThat(defaultPackVendorSku(buyer, afterReceipt.getId())).isEqualTo(catalogProduct.getSku());
    }

    /**
     * MULTI_VENDOR_INVENTORY_DESIGN.md section 7.2 - the "two bags of rice" bug this whole
     * mechanism exists to close. A buyer who already tracks an item under their own SKU, then
     * buys the same real-world product from the marketplace, gets a second, unmatched Product
     * row from materialize() (different SKU, no source-product link yet). The receive-suggestions
     * endpoint should surface the buyer's existing row as a candidate, and answering "yes, same
     * item" on receipt should fold the delivery into it and remove the auto-created duplicate
     * rather than leaving it behind as empty clutter.
     */
    @Test
    void receivingAMarketplaceOrderCanBeLinkedToAnExistingProductInsteadOfLeavingADuplicate() {
        Buyer buyer = signupBuyerAllowedPayOnDelivery("Duplicate Rice Co");
        Product catalogProduct = plantCatalogProduct(40, 1);
        // Same name AND same stock unit as the catalog product but a different SKU - exactly
        // what defeats matchOrCreateBuyerProduct's SKU match and forces it to create a second
        // row, while still being a unit the relink can convert onto trivially (factor 1).
        Product existingProduct =
                ownProductNamed(buyer.clientId(), catalogProduct.getName(), catalogProduct.getUnitOfMeasure());

        int quantity = catalogProduct.getMinOrderQuantity();
        addToCart(buyer, catalogProduct, quantity);
        DeliveryAddressResponse address = createAddress(buyer);
        OrderResponse order = placeOrder(buyer, PaymentMethod.PAY_ON_DELIVERY, address.id());

        Product autoCreated = buyerProductFor(buyer, catalogProduct);
        assertThat(autoCreated.getId()).isNotEqualTo(existingProduct.getId());

        Buyer operator = loginAsPlatformOwner();
        advance(operator, order.id(), OrderStatus.CONFIRMED);
        advance(operator, order.id(), OrderStatus.PROCESSING);
        advance(operator, order.id(), OrderStatus.OUT_FOR_DELIVERY);
        advance(operator, order.id(), OrderStatus.DELIVERED);

        List<OrderItemMatchSuggestionResponse> suggestions = receiveSuggestions(buyer, order.id());
        assertThat(suggestions).hasSize(1);
        UUID lineId = order.items().getFirst().id();
        assertThat(suggestions.getFirst().orderItemId()).isEqualTo(lineId);
        assertThat(suggestions.getFirst().candidates())
                .extracting(OrderItemMatchSuggestionResponse.ProductMatchCandidateResponse::id)
                .contains(existingProduct.getId());

        OrderResponse received = receive(
                buyer,
                order.id(),
                new ReceiveOrderRequest(
                        List.of(new ReceiveOrderRequest.ReceiveOrderLine(lineId, quantity, existingProduct.getId()))));

        assertThat(received.status()).isEqualTo(OrderStatus.RECEIVED);
        assertThat(received.items().getFirst().buyerProductId()).isEqualTo(existingProduct.getId());

        Product merged = productRepository.findById(existingProduct.getId()).orElseThrow();
        assertThat(merged.getQuantityOnHand()).isEqualTo(10 + quantity);
        assertThat(merged.getIncomingQuantity()).isZero();
        assertThat(merged.getSourceProductId()).isEqualTo(catalogProduct.getId());

        // Nothing but this one reservation ever touched the auto-created row, so relinking
        // should have removed it rather than leaving an empty ghost product behind.
        assertThat(productRepository.findById(autoCreated.getId())).isEmpty();
    }

    /**
     * The other half of the same fix: a buyer answering "yes, same item" must not be able to
     * silently write the wrong quantity into a product tracked in an incompatible unit (a bag
     * count landing straight in a kg counter with no conversion). IncomingStockService.receive
     * converts through StockManagementService's own unit resolution rather than copying the
     * quantity raw, so an unconvertible unit has to fail loudly (400) instead - and nothing
     * about either product's stock may change when it does.
     */
    @Test
    void relinkingToAProductWithAnIncompatibleUnitFailsRatherThanCorruptingQuantities() {
        Buyer buyer = signupBuyerAllowedPayOnDelivery("Incompatible Unit Co");
        Product catalogProduct = plantCatalogProduct(40, 1);
        // Same name, but a stock unit the order line's ("bag (25kg)", an arbitrary free-text
        // base unit - see plantCatalogProduct) cannot resolve against at all: no matching code,
        // no shared static category to fall back on.
        Product existingProduct = ownProductNamed(buyer.clientId(), catalogProduct.getName(), "LITER");

        int quantity = catalogProduct.getMinOrderQuantity();
        addToCart(buyer, catalogProduct, quantity);
        DeliveryAddressResponse address = createAddress(buyer);
        OrderResponse order = placeOrder(buyer, PaymentMethod.PAY_ON_DELIVERY, address.id());
        Product autoCreated = buyerProductFor(buyer, catalogProduct);

        Buyer operator = loginAsPlatformOwner();
        advance(operator, order.id(), OrderStatus.CONFIRMED);
        advance(operator, order.id(), OrderStatus.PROCESSING);
        advance(operator, order.id(), OrderStatus.OUT_FOR_DELIVERY);
        advance(operator, order.id(), OrderStatus.DELIVERED);

        UUID lineId = order.items().getFirst().id();
        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/orders/" + order.id() + "/receive",
                HttpMethod.POST,
                new HttpEntity<>(
                        new ReceiveOrderRequest(
                                List.of(new ReceiveOrderRequest.ReceiveOrderLine(lineId, quantity, existingProduct.getId()))),
                        buyer.headers()),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("counted in");

        // The whole attempt rolled back - neither product moved, the auto-created row is still
        // there, and the line is still unreceived and still open to try again.
        Product untouchedExisting = productRepository.findById(existingProduct.getId()).orElseThrow();
        assertThat(untouchedExisting.getQuantityOnHand()).isEqualTo(10);
        assertThat(untouchedExisting.getIncomingQuantity()).isZero();
        Product untouchedAutoCreated = productRepository.findById(autoCreated.getId()).orElseThrow();
        assertThat(untouchedAutoCreated.getIncomingQuantity()).isEqualTo(quantity);
        OrderItem line = orderItemRepository.findById(lineId).orElseThrow();
        assertThat(line.getReceivedQuantity()).isZero();
        assertThat(line.isBuyerProductNewlyCreated()).isTrue();
    }

    /**
     * The recovery path for the failure above: the buyer answers "1 {sellerUnit} = N
     * {myUnit}" themselves, the same per-delivery pack override a manual stock-in's "this
     * delivery came in a different pack" disclosure already offers, and the relink succeeds
     * using exactly that conversion - no support ticket, no giving up and creating a duplicate.
     */
    @Test
    void relinkingWithABuyerSuppliedConversionSucceedsOnAnOtherwiseIncompatibleUnit() {
        Buyer buyer = signupBuyerAllowedPayOnDelivery("Manual Conversion Co");
        Product catalogProduct = plantCatalogProduct(40, 1);
        Product existingProduct = ownProductNamed(buyer.clientId(), catalogProduct.getName(), "LITER");

        int quantity = catalogProduct.getMinOrderQuantity();
        addToCart(buyer, catalogProduct, quantity);
        DeliveryAddressResponse address = createAddress(buyer);
        OrderResponse order = placeOrder(buyer, PaymentMethod.PAY_ON_DELIVERY, address.id());

        Buyer operator = loginAsPlatformOwner();
        advance(operator, order.id(), OrderStatus.CONFIRMED);
        advance(operator, order.id(), OrderStatus.PROCESSING);
        advance(operator, order.id(), OrderStatus.OUT_FOR_DELIVERY);
        advance(operator, order.id(), OrderStatus.DELIVERED);

        UUID lineId = order.items().getFirst().id();
        // "1 bag (25kg) = 40 LITER" - an arbitrary but buyer-asserted conversion; the point is
        // that the buyer's own answer is what the ledger uses, not that this figure is realistic.
        BigDecimal litersPerBag = new BigDecimal("40");
        OrderResponse received = receive(
                buyer,
                order.id(),
                new ReceiveOrderRequest(List.of(new ReceiveOrderRequest.ReceiveOrderLine(
                        lineId, quantity, existingProduct.getId(), catalogProduct.getUnitOfMeasure(), litersPerBag, false))));

        assertThat(received.status()).isEqualTo(OrderStatus.RECEIVED);
        assertThat(received.items().getFirst().buyerProductId()).isEqualTo(existingProduct.getId());

        Product merged = productRepository.findById(existingProduct.getId()).orElseThrow();
        int expectedLiters = litersPerBag.intValue() * quantity;
        assertThat(merged.getQuantityOnHand()).isEqualTo(10 + expectedLiters);
        assertThat(merged.getIncomingQuantity()).isZero();
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
    // (h) MULTI-SELLER: the split, the shared payment, and seller isolation
    // ------------------------------------------------------------------------

    /**
     * The core of the module. One basket holding two sellers' goods becomes TWO orders,
     * each with its own number, seller, subtotal, delivery fee and total.
     *
     * <p>Totals are asserted relationally rather than against hard-coded naira, because
     * the delivery fee and the free-delivery threshold are operator settings this test
     * has no business pinning: what must hold is that each order's arithmetic closes on
     * its own lines, and that nothing is lost or double-counted across the split.
     */
    @Test
    void aMixedSellerCartSplitsIntoOneOrderPerSellerWithItsOwnTotals() {
        Buyer buyer = signupBuyer("Split Basket Co");
        Buyer vendor = signupVendorSeller("Split Vendor Co", "0.0750");

        Product procurePalItem = plantCatalogProduct(40, 1);
        Product vendorItem = plantSellerProduct(vendor.clientId(), "5000.00", 40);

        addToCart(buyer, procurePalItem, 2);
        addToCart(buyer, vendorItem, 3);

        // The quote must show the split BEFORE the buyer commits - a buyer who only saw
        // one combined figure would discover the second delivery fee after paying.
        CheckoutQuoteResponse quote = quote(buyer);
        assertThat(quote.sellerGroups()).hasSize(2);
        assertThat(quote.sellerGroups())
                .extracting(CheckoutQuoteResponse.SellerGroup::sellerId)
                .containsExactlyInAnyOrder(platformOwner().getId(), vendor.clientId());

        OrderResponse placed = placeOrder(buyer, PaymentMethod.MONNIFY, createAddress(buyer).id());

        List<Order> group = ordersInGroupOf(placed.id());
        assertThat(group).hasSize(2);
        // One checkout id across both, and two distinct order numbers.
        assertThat(group).extracting(Order::getCheckoutGroupId).containsOnly(placed.checkoutGroupId());
        assertThat(group).extracting(Order::getOrderNumber).doesNotHaveDuplicates();
        assertThat(group)
                .extracting(Order::getSellerClientId)
                .containsExactlyInAnyOrder(platformOwner().getId(), vendor.clientId());

        for (Order order : group) {
            List<OrderItem> lines = linesOf(order.getId());
            assertThat(lines).isNotEmpty();

            // Each order's subtotal closes on its OWN lines...
            BigDecimal lineSum = lines.stream()
                    .map(OrderItem::getLineTotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(order.getSubtotal()).isEqualByComparingTo(lineSum);
            // ...and its total closes on its own subtotal plus its own delivery fee.
            assertThat(order.getTotal())
                    .isEqualByComparingTo(order.getSubtotal().add(order.getDeliveryFee()));
        }

        // Nothing lost across the split: the goods add up to the quote's subtotal, and
        // the per-seller fees add up to the quote's delivery fee.
        assertThat(group.stream().map(Order::getSubtotal).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(quote.subtotal());
        assertThat(group.stream().map(Order::getDeliveryFee).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(quote.deliveryFee());
        assertThat(group.stream().map(Order::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(quote.total());

        // The cart is emptied once, not once per order.
        assertThat(getCart(buyer).items()).isEmpty();
    }

    /**
     * The buyer's order history has to read as one shopping trip, not three coincidences.
     */
    @Test
    void theBuyerCanSeeThatOneCheckoutProducedSeveralOrders() {
        Buyer buyer = signupBuyer("Grouped History Co");
        Buyer vendor = signupVendorSeller("History Vendor Co", "0.0500");

        addToCart(buyer, plantCatalogProduct(20, 1), 1);
        addToCart(buyer, plantSellerProduct(vendor.clientId(), "9000.00", 20), 1);
        OrderResponse placed = placeOrder(buyer, PaymentMethod.MONNIFY, createAddress(buyer).id());

        OrderResponse detail = getOrder(buyer, placed.id());
        assertThat(detail.checkoutGroupId()).isNotNull();
        assertThat(detail.siblingOrders()).hasSize(1);
        assertThat(detail.siblingOrders().getFirst().orderNumber()).isNotEqualTo(detail.orderNumber());
        // Each order names who is fulfilling it, so a three-order history is readable.
        assertThat(detail.seller()).isNotNull();
        assertThat(detail.siblingOrders().getFirst().seller()).isNotNull();

        // The list projection carries the group id so the history can badge the trip,
        // and the seller so each row says who it is from.
        List<OrderSummaryResponse> history = listOrders(buyer);
        assertThat(history)
                .filteredOn(row -> detail.checkoutGroupId().equals(row.checkoutGroupId()))
                .hasSize(2)
                .allSatisfy(row -> assertThat(row.seller()).isNotNull());
    }

    /**
     * ONE payment settles EVERY order in the checkout group, atomically - and a replay
     * changes nothing.
     *
     * <p>Driven through {@code applyPaymentSuccess}, which is the exact seam the Monnify
     * webhook, the browser return-verify and the reconciliation sweep all funnel into
     * (see PaymentApplicationService). The amount is the GROUP total: settling on the
     * anchor order's total alone would accept a payment covering half the basket, which
     * is the failure this fan-out exists to prevent.
     */
    @Test
    void oneWebhookSettlesEveryOrderInTheCheckoutGroupAndAReplayChangesNothing() {
        Buyer buyer = signupBuyer("One Payment Many Orders Co");
        Buyer vendor = signupVendorSeller("Settled Vendor Co", "0.1000");

        Product procurePalItem = plantCatalogProduct(30, 1);
        Product vendorItem = plantSellerProduct(vendor.clientId(), "7000.00", 30);
        addToCart(buyer, procurePalItem, 2);
        addToCart(buyer, vendorItem, 2);

        OrderResponse placed = placeOrder(buyer, PaymentMethod.MONNIFY, createAddress(buyer).id());
        List<Order> group = ordersInGroupOf(placed.id());
        assertThat(group).hasSize(2);
        assertThat(group).allSatisfy(order -> {
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
            assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        });

        BigDecimal groupTotal = group.stream().map(Order::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add);

        // The payment module asks the order module what this payment covers; it must
        // answer with the whole group and its summed total, not the anchor's.
        OrderPaymentContext context = orderPaymentApplication.loadPaymentContext(placed.id());
        assertThat(context.orderIds()).hasSize(2);
        assertThat(context.isSplit()).isTrue();
        assertThat(context.total()).isEqualByComparingTo(groupTotal);

        PaymentSuccess success = new PaymentSuccess(
                "PP-SPLIT-" + UUID.randomUUID().toString().substring(0, 8),
                "MNFY-" + UUID.randomUUID().toString().substring(0, 8),
                groupTotal,
                OffsetDateTime.now(),
                "CARD",
                PaymentVerificationSource.WEBHOOK);

        orderPaymentApplication.applyPaymentSuccess(placed.id(), success);

        assertThat(ordersInGroupOf(placed.id())).allSatisfy(order -> {
            assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PLACED);
        });

        // Incoming stock landed once per line.
        int procurePalIncoming = buyerProductFor(buyer, procurePalItem).getIncomingQuantity();
        int vendorIncoming = buyerProductFor(buyer, vendorItem).getIncomingQuantity();
        assertThat(procurePalIncoming).isEqualTo(2);
        assertThat(vendorIncoming).isEqualTo(2);

        // THE REPLAY. Monnify retries, and the sweep arrives late; both land here.
        orderPaymentApplication.applyPaymentSuccess(placed.id(), success);
        orderPaymentApplication.applyPaymentSuccess(placed.id(), success);

        assertThat(buyerProductFor(buyer, procurePalItem).getIncomingQuantity()).isEqualTo(procurePalIncoming);
        assertThat(buyerProductFor(buyer, vendorItem).getIncomingQuantity()).isEqualTo(vendorIncoming);
        assertThat(ordersInGroupOf(placed.id()))
                .allSatisfy(order -> assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PAID));
    }

    /**
     * The commission rate is frozen on the line at sale time, from the SELLER's rate -
     * and is null for ProcurePal's own lines, because commission is a fact about a
     * third-party sale and ProcurePal is not a third party to itself.
     *
     * <p>The rate is then changed and the stamp re-read: renegotiating a vendor's terms
     * must not retroactively rewrite what the platform earned on orders they have
     * already shipped.
     */
    @Test
    void commissionRateIsStampedFromTheSellerAtSaleTimeAndNeverRewritten() {
        Buyer buyer = signupBuyer("Commission Stamp Co");
        Buyer vendor = signupVendorSeller("Rate Vendor Co", "0.0750");

        addToCart(buyer, plantCatalogProduct(20, 1), 1);
        addToCart(buyer, plantSellerProduct(vendor.clientId(), "6000.00", 20), 1);
        OrderResponse placed = placeOrder(buyer, PaymentMethod.MONNIFY, createAddress(buyer).id());

        for (Order order : ordersInGroupOf(placed.id())) {
            List<OrderItem> lines = linesOf(order.getId());
            if (order.getSellerClientId().equals(vendor.clientId())) {
                assertThat(lines)
                        .allSatisfy(line -> assertThat(line.getCommissionRate())
                                .isEqualByComparingTo(new BigDecimal("0.0750")));
            } else {
                // Null, never 0.0000: "no commission applies" is not "a 0% deal".
                assertThat(lines).allSatisfy(line -> assertThat(line.getCommissionRate()).isNull());
            }
        }

        // Renegotiate, and confirm history did not move.
        Client vendorClient = clientRepository.findById(vendor.clientId()).orElseThrow();
        vendorClient.setCommissionRate(new BigDecimal("0.2000"));
        clientRepository.saveAndFlush(vendorClient);

        for (Order order : ordersInGroupOf(placed.id())) {
            if (order.getSellerClientId().equals(vendor.clientId())) {
                assertThat(linesOf(order.getId()))
                        .allSatisfy(line -> assertThat(line.getCommissionRate())
                                .isEqualByComparingTo(new BigDecimal("0.0750")));
            }
        }
    }

    /**
     * THE CROSS-VENDOR LEAK TEST. A vendor sees and advances only their own orders.
     *
     * <p>Every caller here holds MANAGE_MARKETPLACE_ORDERS, so nothing below is decided
     * by {@code @PreAuthorize} - it is decided by VendorGuard plus the seller_client_id
     * predicate, which is the claim worth testing.
     */
    @Test
    void aVendorSeesAndAdvancesOnlyTheirOwnOrdersAndIsDeniedAnotherSellers() {
        Buyer buyer = signupBuyer("Two Vendor Basket Co");
        Buyer vendorA = signupVendorSeller("Vendor A Co", "0.0500");
        Buyer vendorB = signupVendorSeller("Vendor B Co", "0.0500");

        addToCart(buyer, plantSellerProduct(vendorA.clientId(), "4000.00", 20), 2);
        addToCart(buyer, plantSellerProduct(vendorB.clientId(), "4000.00", 20), 2);
        addToCart(buyer, plantCatalogProduct(20, 1), 1);

        OrderResponse placed = placeOrder(buyer, PaymentMethod.MONNIFY, createAddress(buyer).id());
        List<Order> group = ordersInGroupOf(placed.id());
        assertThat(group).hasSize(3);

        UUID orderOfA = orderFor(group, vendorA.clientId());
        UUID orderOfB = orderFor(group, vendorB.clientId());
        UUID orderOfProcurePal = orderFor(group, platformOwner().getId());

        // (1) The queue shows only your own.
        assertThat(fulfilmentQueueOf(vendorA))
                .extracting(OrderSummaryResponse::id)
                .contains(orderOfA)
                .doesNotContain(orderOfB, orderOfProcurePal);
        assertThat(fulfilmentQueueOf(vendorB))
                .extracting(OrderSummaryResponse::id)
                .contains(orderOfB)
                .doesNotContain(orderOfA, orderOfProcurePal);

        // (2) Reading another seller's order by id is a 404, not somebody else's
        // customer and delivery address.
        assertThat(restTemplate
                        .exchange(
                                "/api/marketplace/admin/orders/" + orderOfB,
                                HttpMethod.GET,
                                new HttpEntity<>(vendorA.headers()),
                                ApiError.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // (3) Advancing another seller's order is refused.
        assertThat(advanceExpectingFailure(vendorA, orderOfB, OrderStatus.CONFIRMED).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(orderRepository.findById(orderOfB).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING_PAYMENT);


        // (4) But a vendor CAN advance their own, once it is paid. Paid through the
        // shared checkout, which settles all three at once - so this also confirms the
        // fan-out reaches a vendor's order and not just the anchor.
        BigDecimal groupTotal = group.stream().map(Order::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        orderPaymentApplication.applyPaymentSuccess(
                placed.id(),
                new PaymentSuccess(
                        "PP-ISO-" + UUID.randomUUID().toString().substring(0, 8),
                        null,
                        groupTotal,
                        OffsetDateTime.now(),
                        "CARD",
                        PaymentVerificationSource.WEBHOOK));
        assertThat(orderRepository.findById(orderOfA).orElseThrow().getStatus()).isEqualTo(OrderStatus.PLACED);

        assertThat(advance(vendorA, orderOfA, OrderStatus.CONFIRMED).status())
                .isEqualTo(OrderStatus.CONFIRMED);
        // ...and still cannot touch B's, which the same payment also moved to PLACED.
        assertThat(advanceExpectingFailure(vendorA, orderOfB, OrderStatus.CONFIRMED).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * ProcurePal is the platform owner, and that confers NO ability to advance a
     * vendor's order through the fulfilment endpoints.
     *
     * <p>Dispatching another company's goods is not an operator capability - it is a
     * fulfilment action only the party holding the stock can honestly take. An operator
     * override, if ever needed, belongs on a super-admin surface with its own audit
     * trail rather than folded into the normal queue.
     */
    @Test
    void procurePalCannotAdvanceAVendorsOrder() {
        Buyer buyer = signupBuyer("Operator Overreach Co");
        Buyer vendor = signupVendorSeller("Untouchable Vendor Co", "0.0500");
        Buyer operator = loginAsPlatformOwner();

        addToCart(buyer, plantSellerProduct(vendor.clientId(), "8000.00", 20), 1);
        OrderResponse placed = placeOrder(buyer, PaymentMethod.MONNIFY, createAddress(buyer).id());

        assertThat(orderRepository.findById(placed.id()).orElseThrow().getSellerClientId())
                .isEqualTo(vendor.clientId());

        assertThat(advanceExpectingFailure(operator, placed.id(), OrderStatus.CONFIRMED).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(orderRepository.findById(placed.id()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING_PAYMENT);

        // Nor does it appear in ProcurePal's own queue.
        assertThat(fulfilmentQueueOf(operator))
                .extracting(OrderSummaryResponse::id)
                .doesNotContain(placed.id());
    }

    /**
     * Pay on delivery is refused as soon as a vendor's goods are in the basket, and is
     * untouched for ProcurePal's own.
     *
     * <p>The v1 ruling, with its reasoning and its removal condition, is on
     * CheckoutService.payOnDeliveryReasons: the platform's rider collecting cash for a
     * third party's goods creates a same-day liability that needs the vendor ledger to
     * record, and that ledger is a later module.
     */
    @Test
    void payOnDeliveryIsRefusedWhenTheBasketHoldsAVendorsGoodsButNotForProcurePalsOwn() {
        Buyer buyer = signupBuyerAllowedPayOnDelivery("POD Split Co");
        Buyer vendor = signupVendorSeller("POD Vendor Co", "0.0500");

        // ProcurePal only: pay on delivery still works exactly as it always has.
        Product procurePalItem = plantCatalogProduct(30, 1);
        addToCart(buyer, procurePalItem, 1);
        assertThat(quote(buyer).payOnDeliveryEligible()).isTrue();

        // Add a vendor line, and the option closes with an explanation.
        addToCart(buyer, plantSellerProduct(vendor.clientId(), "5000.00", 30), 1);
        CheckoutQuoteResponse mixed = quote(buyer);
        assertThat(mixed.payOnDeliveryEligible()).isFalse();
        assertThat(mixed.payOnDeliveryReasons()).anyMatch(reason -> reason.contains("marketplace vendors"));

        // And the server refuses it, rather than relying on the UI to grey a button out.
        ResponseEntity<ApiError> refused = restTemplate.exchange(
                "/api/orders",
                HttpMethod.POST,
                new HttpEntity<>(
                        new PlaceOrderRequest(
                                PaymentMethod.PAY_ON_DELIVERY, createAddress(buyer).id(), null, null, null),
                        buyer.headers()),
                ApiError.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).isNotNull();
        assertThat(refused.getBody().message()).contains("marketplace vendors");
    }

    /** The seller of an order in a group, for the isolation tests. */
    private static UUID orderFor(List<Order> group, UUID sellerClientId) {
        return group.stream()
                .filter(order -> order.getSellerClientId().equals(sellerClientId))
                .map(Order::getId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no order for seller " + sellerClientId));
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
        return ownProductNamed(clientId, "Own Item " + UUID.randomUUID().toString().substring(0, 8), null);
    }

    /** Like {@link #ownProductFor}, but with a caller-chosen name - the section 7.2 duplicate-nudge
     *  tests need a name that matches a catalog product exactly, and a random SKU that doesn't. */
    private Product ownProductNamed(UUID clientId, String name) {
        return ownProductNamed(clientId, name, null);
    }

    /**
     * Like {@link #ownProductNamed(UUID, String)}, but also lets a test pin the stock unit - the
     * section 7.2 relink tests need to control, deliberately, whether it matches the order line's
     * unit or not.
     */
    private Product ownProductNamed(UUID clientId, String name, String unitOfMeasure) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        Product product = Product.builder()
                .name(name)
                .sku("OWN-" + unique)
                .unitPrice(new BigDecimal("1000.00"))
                .quantityOnHand(10)
                .active(true)
                .marketplaceListed(false)
                .unitOfMeasure(unitOfMeasure)
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
                // ProcurePal's own catalogue is auto-approved (ProductModerationRules),
                // and these fixtures stand in for exactly that. Set explicitly because
                // this bypasses the service that would otherwise stamp it: the entity
                // default is PENDING, which fails closed and would make every product
                // planted here invisible to the storefront.
                .approvalStatus(ProductApprovalStatus.APPROVED)
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

    /** The vendorSku on a buyer product's preferred vendor's default pack - see StockInRequest.vendorSku. */
    private String defaultPackVendorSku(Buyer buyer, UUID buyerProductId) {
        ProductVendor vendor = productVendorRepository
                .findByClientIdAndProductIdAndIsPreferredTrue(buyer.clientId(), buyerProductId)
                .orElseThrow(() -> new AssertionError("expected a preferred vendor line for this product"));
        ProductVendorPack pack = productVendorPackRepository
                .findByProductVendorIdAndIsDefaultTrue(vendor.getId())
                .orElseThrow(() -> new AssertionError("expected a default pack for this vendor line"));
        return pack.getVendorSku();
    }

    /** Turns on SKU auto-generation for a tenant, the same PUT the SKU settings screen calls. */
    private void enableSkuGeneration(Buyer buyer, String pattern) {
        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/products/sku-settings",
                HttpMethod.PUT,
                new HttpEntity<>(
                        new UpdateProductSkuSettingsRequest(true, pattern, SkuResetCadence.NEVER), buyer.headers()),
                Void.class);
        assertThat(response.getStatusCode()).as("enable sku generation").isEqualTo(HttpStatus.OK);
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

    private List<OrderItemMatchSuggestionResponse> receiveSuggestions(Buyer buyer, UUID orderId) {
        return restTemplate
                .exchange(
                        "/api/orders/" + orderId + "/receive-suggestions",
                        HttpMethod.GET,
                        new HttpEntity<>(buyer.headers()),
                        new ParameterizedTypeReference<List<OrderItemMatchSuggestionResponse>>() {})
                .getBody();
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


    // ------------------------------------------------------------------------
    // multi-seller fixtures
    // ------------------------------------------------------------------------

    /**
     * A vendor seller: an ordinary signup whose clients row is then flipped to
     * client_type = VENDOR with a commission rate agreed.
     *
     * <p>Flipped rather than onboarded through the waitlist because vendor onboarding
     * belongs to another module, and because this shape exercises a property worth
     * pinning down: the user keeps the OWNER role, which already holds
     * MANAGE_MARKETPLACE_ORDERS. So every authorization assertion below passes the
     * {@code @PreAuthorize} gate and is decided by VendorGuard plus the
     * seller_client_id predicate - which is exactly the claim the fulfilment module
     * makes about why the permission alone is not sufficient.
     */
    private Buyer signupVendorSeller(String name, String commissionRate) {
        Buyer seller = signupBuyer(name);
        Client client = clientRepository.findById(seller.clientId()).orElseThrow();
        client.setClientType(ClientType.VENDOR);
        client.setCommissionRate(new BigDecimal(commissionRate));
        clientRepository.saveAndFlush(client);
        return seller;
    }

    /**
     * A listed, approved catalog product owned by an arbitrary seller.
     *
     * <p>approvalStatus is set explicitly because this writes through the repository and
     * so takes the entity's PENDING default; a real vendor product would reach APPROVED
     * only after moderation, which is that module's test, not this one's.
     */
    private Product plantSellerProduct(UUID sellerClientId, String unitPrice, int quantityOnHand) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        Product product = Product.builder()
                .name("Seller Item " + unique)
                .sku("SELL-" + unique)
                .slug("sell-" + unique)
                .description("Planted by MarketplaceOrderIntegrationTest.")
                .unitPrice(new BigDecimal(unitPrice))
                .quantityOnHand(quantityOnHand)
                .active(true)
                .marketplaceListed(true)
                .approvalStatus(ProductApprovalStatus.APPROVED)
                .unitOfMeasure("carton")
                .minOrderQuantity(1)
                .build();
        TenantContext.set(sellerClientId);
        try {
            Product saved = productRepository.saveAndFlush(product);
            plantedCatalogProductIds.add(saved.getId());
            return saved;
        } finally {
            TenantContext.clear();
        }
    }

    /** Every order one checkout produced, order-number ascending. */
    private List<Order> ordersInGroupOf(UUID orderId) {
        Order anchor = orderRepository.findById(orderId).orElseThrow();
        return orderRepository.findAllByCheckoutGroupIdOrderByOrderNumberAsc(anchor.getCheckoutGroupId());
    }

    private List<OrderItem> linesOf(UUID orderId) {
        return orderItemRepository.findAllByOrderIdOrderByCreatedAtAsc(orderId);
    }

    private ResponseEntity<ApiError> advanceExpectingFailure(Buyer caller, UUID orderId, OrderStatus status) {
        return restTemplate.exchange(
                "/api/marketplace/admin/orders/" + orderId + "/status",
                HttpMethod.POST,
                new HttpEntity<>(new AdvanceOrderStatusRequest(status, "Attempted by " + caller.clientId()),
                        caller.headers()),
                ApiError.class);
    }

    private List<OrderSummaryResponse> fulfilmentQueueOf(Buyer seller) {
        return restTemplate
                .exchange(
                        "/api/marketplace/admin/orders?size=200",
                        HttpMethod.GET,
                        new HttpEntity<>(seller.headers()),
                        new ParameterizedTypeReference<TestPage<OrderSummaryResponse>>() {})
                .getBody()
                .content();
    }

    private void assertForbidden(HttpMethod method, String path, Object body, Buyer caller) {
        ResponseEntity<ApiError> response = restTemplate.exchange(
                path, method, new HttpEntity<>(body, caller.headers()), ApiError.class);
        assertThat(response.getStatusCode()).as("%s %s", method, path).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
