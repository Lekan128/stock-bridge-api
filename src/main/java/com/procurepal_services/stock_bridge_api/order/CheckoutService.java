package com.procurepal_services.stock_bridge_api.order;

import com.procurepal_services.stock_bridge_api.address.dto.DeliveryAddressResponse;
import com.procurepal_services.stock_bridge_api.address.DeliveryAddressService;
import com.procurepal_services.stock_bridge_api.entity.Cart;
import com.procurepal_services.stock_bridge_api.entity.CartItem;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.DeliveryAddress;
import com.procurepal_services.stock_bridge_api.entity.MarketplaceSettings;
import com.procurepal_services.stock_bridge_api.entity.PaymentMethod;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.cart.CartService;
import com.procurepal_services.stock_bridge_api.marketplace.BuyerCatalogLookup;
import com.procurepal_services.stock_bridge_api.order.dto.CheckoutQuoteRequest;
import com.procurepal_services.stock_bridge_api.order.dto.CheckoutQuoteResponse;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.MarketplaceSettingsRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prices a cart, and decides whether it may become an order.
 *
 * <h2>One computation, two callers</h2>
 * {@code POST /api/checkout/quote} and {@code POST /api/orders} both run
 * {@link #price()}. That is the point: a quote that promised ₦482,500 and an order
 * that charged something else would be a trust problem, not a bug report. Prices come
 * from the live catalog row every time - the cart stores none - so a repricing shows
 * up in the quote before the buyer commits, and the order is written from the same
 * numbers a moment later.
 *
 * <b>Nothing here reads a price, a total or a line count from the request body.</b>
 * The client chooses a payment method and an address; the server decides what it
 * costs.
 */
@Service
@RequiredArgsConstructor
public class CheckoutService {

    private final CartService cartService;
    private final BuyerCatalogLookup buyerCatalogLookup;
    private final MarketplaceSettingsRepository marketplaceSettingsRepository;
    private final ClientRepository clientRepository;
    private final DeliveryAddressService deliveryAddressService;
    private final CatalogStockService catalogStockService;

    /** One priced line. Carries the live catalog product so order creation can snapshot from it. */
    public record PricedLine(
            CartItem item, Product product, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
    }

    /** The whole priced basket, plus every reason it might not be checkout-able. */
    public record PricedCart(
            Cart cart,
            List<PricedLine> lines,
            BigDecimal subtotal,
            BigDecimal deliveryFee,
            BigDecimal total,
            MarketplaceSettings settings,
            List<String> blockers,
            List<CheckoutQuoteResponse.UnavailableLine> unavailable) {

        public boolean isCheckoutable() {
            return blockers.isEmpty();
        }

        public int itemCount() {
            return lines.stream().mapToInt(PricedLine::quantity).sum();
        }
    }

    /**
     * Not readOnly, despite being a pure read from the caller's point of view: pricing
     * resolves the company's cart, and a company that has never added anything does not
     * have one yet. Find-or-create has to be able to INSERT that row, which a read-only
     * transaction (FlushMode.MANUAL) silently refuses - the symptom was a 500 on the
     * very first visit to checkout and nowhere else.
     */
    @Transactional
    public CheckoutQuoteResponse quote(CheckoutQuoteRequest request) {
        PricedCart priced = price();
        MarketplaceSettings settings = priced.settings();
        Client client = requireClient();

        DeliveryAddress address = deliveryAddressService
                .resolveForCheckout(request == null ? null : request.deliveryAddressId())
                .orElse(null);

        List<String> blockers = new ArrayList<>(priced.blockers());
        if (address == null) {
            blockers.add("Add a delivery address before checking out.");
        }

        List<String> payOnDeliveryReasons = payOnDeliveryReasons(client, settings, priced.total());
        BigDecimal toFreeDelivery = settings.getFreeDeliveryThreshold().subtract(priced.subtotal());

        return new CheckoutQuoteResponse(
                "NGN",
                priced.subtotal(),
                priced.deliveryFee(),
                priced.total(),
                priced.itemCount(),
                priced.lines().size(),
                settings.getFreeDeliveryThreshold(),
                toFreeDelivery.signum() > 0 ? toFreeDelivery : BigDecimal.ZERO,
                priced.deliveryFee().signum() == 0,
                settings.getMinimumOrderValue(),
                priced.subtotal().compareTo(settings.getMinimumOrderValue()) >= 0,
                blockers.isEmpty(),
                List.copyOf(blockers),
                payOnDeliveryReasons.isEmpty(),
                List.copyOf(payOnDeliveryReasons),
                settings.getPayOnDeliveryMaxOrderValue(),
                address == null ? null : DeliveryAddressResponse.from(address),
                priced.unavailable());
    }

    /**
     * Re-reads every line against the live catalog and totals it. Called inside the
     * order-creation transaction too, so the validation that produced the quote is
     * the same validation that gates the write. Not readOnly for the same reason as
     * {@link #quote} - it may have to create the company's cart row.
     */
    @Transactional
    public PricedCart price() {
        MarketplaceSettings settings = requireSettings();
        Cart cart = cartService.findOrCreateCart();
        List<CartItem> items = cartService.itemsOf(cart);
        Map<UUID, Product> catalog =
                buyerCatalogLookup.findAllByIds(items.stream().map(CartItem::getProductId).toList());

        List<PricedLine> lines = new ArrayList<>();
        List<CheckoutQuoteResponse.UnavailableLine> unavailable = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;

        for (CartItem item : items) {
            Product product = catalog.get(item.getProductId());
            String reason = unavailableReason(product, item.getQuantity());
            if (reason != null) {
                unavailable.add(new CheckoutQuoteResponse.UnavailableLine(
                        item.getProductId(),
                        product == null ? "Unavailable product" : product.getName(),
                        reason));
                continue;
            }
            BigDecimal lineTotal = product.getUnitPrice()
                    .multiply(BigDecimal.valueOf(item.getQuantity()))
                    .setScale(2, RoundingMode.HALF_UP);
            lines.add(new PricedLine(item, product, item.getQuantity(), product.getUnitPrice(), lineTotal));
            subtotal = subtotal.add(lineTotal);
        }
        subtotal = subtotal.setScale(2, RoundingMode.HALF_UP);

        // Free delivery is judged on the goods subtotal, not the total - otherwise the
        // delivery fee could push an order over its own free-delivery threshold, which
        // is a paradox a customer would (rightly) call a bug.
        BigDecimal deliveryFee = subtotal.compareTo(settings.getFreeDeliveryThreshold()) >= 0
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : settings.getDeliveryFee().setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = subtotal.add(deliveryFee).setScale(2, RoundingMode.HALF_UP);

        List<String> blockers = new ArrayList<>();
        if (lines.isEmpty()) {
            blockers.add("Your cart is empty.");
        }
        if (!unavailable.isEmpty()) {
            blockers.add("Some items are no longer available. Remove them to continue.");
        }
        if (!lines.isEmpty() && subtotal.compareTo(settings.getMinimumOrderValue()) < 0) {
            blockers.add("The minimum order value is NGN " + settings.getMinimumOrderValue() + ".");
        }

        return new PricedCart(cart, lines, subtotal, deliveryFee, total, settings, blockers, unavailable);
    }

    /**
     * The three independent gates on pay-on-delivery (contract §5). Returned as
     * reasons rather than a boolean so checkout can grey the option out and explain
     * itself - "why can't I pay on delivery" is otherwise a support call.
     */
    public List<String> payOnDeliveryReasons(Client client, MarketplaceSettings settings, BigDecimal total) {
        List<String> reasons = new ArrayList<>();
        if (!settings.isPayOnDeliveryEnabled()) {
            reasons.add("Pay on delivery is currently unavailable.");
        }
        if (!client.isPayOnDeliveryAllowed()) {
            reasons.add("Your account is set to prepaid. Contact ProcurePal to enable pay on delivery.");
        }
        if (total.compareTo(settings.getPayOnDeliveryMaxOrderValue()) > 0) {
            reasons.add("Pay on delivery is only available on orders up to NGN "
                    + settings.getPayOnDeliveryMaxOrderValue() + ".");
        }
        return reasons;
    }

    /** Throws if the chosen method cannot be used for this basket. Called at order creation, not at quote. */
    public void requirePaymentMethodAllowed(PaymentMethod method, Client client, PricedCart priced) {
        if (method != PaymentMethod.PAY_ON_DELIVERY) {
            return;
        }
        List<String> reasons = payOnDeliveryReasons(client, priced.settings(), priced.total());
        if (!reasons.isEmpty()) {
            throw new CheckoutNotAllowedException(String.join(" ", reasons));
        }
    }

    @Transactional(readOnly = true)
    public MarketplaceSettings requireSettings() {
        return marketplaceSettingsRepository
                .findBySingletonTrue()
                // Seeded by V6. An empty table is a broken database, not a state to
                // design around - guessing a delivery fee is worse than an error.
                .orElseThrow(() -> new IllegalStateException(
                        "marketplace_settings has no row; the marketplace cannot price an order"));
    }

    @Transactional(readOnly = true)
    public Client requireClient() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context set");
        }
        return clientRepository.findById(tenantId).orElseThrow(() -> new IllegalStateException("Unknown tenant"));
    }

    /**
     * Availability is measured against what is left to SELL, not against
     * quantity_on_hand: ProcurePal's stock only falls at dispatch, so on-hand still
     * includes everything sold this morning and not yet on a van. See
     * CatalogStockService.
     */
    private String unavailableReason(Product product, int quantity) {
        if (product == null) {
            return "This product is no longer sold.";
        }
        if (!product.isMarketplaceListed() || !product.isActive()) {
            return "This product has been withdrawn from the marketplace.";
        }
        if (quantity < product.getMinOrderQuantity()) {
            return "The minimum order quantity is " + product.getMinOrderQuantity() + ".";
        }
        int available = catalogStockService.availableToSell(product);
        if (available < quantity) {
            return available <= 0 ? "Out of stock." : "Only " + available + " left.";
        }
        return null;
    }
}
