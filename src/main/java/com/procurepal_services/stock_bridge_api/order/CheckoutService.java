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
import com.procurepal_services.stock_bridge_api.marketplace.SellerDirectory;
import com.procurepal_services.stock_bridge_api.order.dto.CheckoutQuoteRequest;
import com.procurepal_services.stock_bridge_api.order.dto.CheckoutQuoteResponse;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.MarketplaceSettingsRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
 *
 * <h2>One basket, several sellers</h2>
 * A cart may hold products from several sellers, and at checkout it splits into one
 * order per seller. Pricing therefore produces {@link PricedSellerGroup}s as well as
 * a basket total, and the group is the unit that becomes an Order. Two rules follow,
 * and they are deliberately different from each other:
 *
 * <ul>
 *   <li><b>Delivery is charged PER SELLER.</b> Each group pays its own fee and clears
 *       the free-delivery threshold on its own subtotal. The reason is physical: three
 *       sellers means three warehouses, three pick-and-packs and three vans, and
 *       {@code orders.delivery_fee} is a column on the ORDER, so a basket-wide fee
 *       would have to be apportioned into those columns anyway. Any apportionment
 *       makes one order's {@code subtotal + delivery_fee = total} arithmetic depend on
 *       the other orders in the basket, which breaks every per-order invoice, refund
 *       and cancellation the moment one of them is cancelled. A self-contained fee per
 *       order is the only version where cancelling one order leaves the others
 *       correct.</li>
 *   <li><b>The minimum order value stays BASKET-WIDE.</b> It is not the same kind of
 *       rule: the fee prices a physical delivery, while the minimum asks "is this
 *       shopping trip worth processing at all", and the buyer makes one trip and pays
 *       once. Applying it per seller would block a ₦60,000 basket because ₦2,000 of it
 *       came from a second vendor, which reads as a bug to the buyer.
 *       <p>The accepted consequence, stated so nobody finds it by surprise: a seller
 *       CAN receive an order below the platform minimum, as long as the whole basket
 *       cleared it. Each such order still carries its own delivery fee, so it is not
 *       being fulfilled for free. If per-seller minimums are ever wanted they belong
 *       on the seller, not on marketplace_settings.</li>
 * </ul>
 *
 * <h2>Pay on delivery is refused when a vendor is involved</h2>
 * See {@link #payOnDeliveryReasons}. This is a v1 restriction with a specific reason
 * and a specific removal condition, both documented there.
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
    private final SellerDirectory sellerDirectory;

    /** One priced line. Carries the live catalog product so order creation can snapshot from it. */
    public record PricedLine(
            CartItem item, Product product, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
    }

    /**
     * One seller's share of the basket - and therefore exactly one future Order.
     *
     * <p>{@code commissionRate} is resolved HERE, at pricing time, rather than being
     * looked up again when the order rows are written. That is what makes the stamp on
     * {@link com.procurepal_services.stock_bridge_api.entity.OrderItem#getCommissionRate()}
     * a snapshot of a single moment: quote and order run the same code, so the rate the
     * buyer was quoted against is the rate the platform records having earned, even if
     * an operator renegotiates the vendor's rate between the two calls.
     *
     * <p>Null for the platform owner's group, never zero - ProcurePal selling its own
     * goods is not a 0% commission arrangement, it is the absence of one. See the
     * OrderItem field javadoc.
     */
    public record PricedSellerGroup(
            UUID sellerId,
            String sellerName,
            String sellerSlug,
            String sellerLogoUrl,
            boolean platformOwner,
            BigDecimal commissionRate,
            List<PricedLine> lines,
            BigDecimal subtotal,
            BigDecimal deliveryFee,
            BigDecimal total) {

        public int itemCount() {
            return lines.stream().mapToInt(PricedLine::quantity).sum();
        }
    }

    /** The whole priced basket, plus every reason it might not be checkout-able. */
    public record PricedCart(
            Cart cart,
            List<PricedLine> lines,
            List<PricedSellerGroup> groups,
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

        /** True when this basket will produce more than one order. */
        public boolean isSplit() {
            return groups.size() > 1;
        }

        /** Any group whose seller is not the platform owner - the pay-on-delivery gate turns on this. */
        public boolean hasVendorSeller() {
            return groups.stream().anyMatch(group -> !group.platformOwner());
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

        List<String> payOnDeliveryReasons = payOnDeliveryReasons(client, settings, priced.total(), priced);
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
                priced.unavailable(),
                toSellerGroups(priced));
    }

    /** The priced groups, projected for the checkout screen. Seller identity only - no commission rate. */
    private static List<CheckoutQuoteResponse.SellerGroup> toSellerGroups(PricedCart priced) {
        return priced.groups().stream()
                .map(group -> new CheckoutQuoteResponse.SellerGroup(
                        group.sellerId(),
                        group.sellerName(),
                        group.sellerSlug(),
                        group.sellerLogoUrl(),
                        group.platformOwner(),
                        group.itemCount(),
                        group.lines().size(),
                        group.subtotal(),
                        group.deliveryFee(),
                        group.total(),
                        group.deliveryFee().signum() == 0))
                .toList();
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

        // Insertion-ordered so the group order is stable across quote and order
        // creation, and therefore across two renders of the same checkout screen.
        Map<UUID, List<PricedLine>> linesBySeller = new LinkedHashMap<>();

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
            PricedLine line = new PricedLine(item, product, item.getQuantity(), product.getUnitPrice(), lineTotal);
            lines.add(line);
            linesBySeller
                    .computeIfAbsent(product.getClientId(), sellerId -> new ArrayList<>())
                    .add(line);
            subtotal = subtotal.add(lineTotal);
        }
        subtotal = subtotal.setScale(2, RoundingMode.HALF_UP);

        List<PricedSellerGroup> groups = priceGroups(linesBySeller, settings);

        // The basket's delivery fee is the SUM of the per-seller fees, not a fee of its
        // own. It exists so the quote can show one number; the authoritative figures are
        // the per-group ones, which are what land in orders.delivery_fee.
        BigDecimal deliveryFee = groups.stream()
                .map(PricedSellerGroup::deliveryFee)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = subtotal.add(deliveryFee).setScale(2, RoundingMode.HALF_UP);

        List<String> blockers = new ArrayList<>();
        if (lines.isEmpty()) {
            blockers.add("Your cart is empty.");
        }
        if (!unavailable.isEmpty()) {
            blockers.add("Some items are no longer available. Remove them to continue.");
        }
        // Basket-wide, deliberately - see the class javadoc for why this is not applied
        // per seller the way the delivery fee is.
        if (!lines.isEmpty() && subtotal.compareTo(settings.getMinimumOrderValue()) < 0) {
            blockers.add("The minimum order value is NGN " + settings.getMinimumOrderValue() + ".");
        }

        return new PricedCart(cart, lines, groups, subtotal, deliveryFee, total, settings, blockers, unavailable);
    }

    /**
     * Turns the per-seller line buckets into priced groups: one future order each.
     *
     * <p>The seller is read through {@link SellerDirectory#findSellerOfRecord} rather
     * than the active-seller list. By this point the lines have ALREADY passed
     * {@link BuyerCatalogLookup}, which refuses products whose owner may not sell, so
     * the active check has been made; what is wanted here is the seller's name and rate
     * even in the corner case where they were suspended microseconds ago, so a checkout
     * in flight renders with a name rather than blank. Availability is the earlier
     * gate's job, not this one's.
     */
    private List<PricedSellerGroup> priceGroups(
            Map<UUID, List<PricedLine>> linesBySeller, MarketplaceSettings settings) {
        List<PricedSellerGroup> groups = new ArrayList<>(linesBySeller.size());

        for (Map.Entry<UUID, List<PricedLine>> entry : linesBySeller.entrySet()) {
            UUID sellerId = entry.getKey();
            List<PricedLine> sellerLines = entry.getValue();
            Client seller = sellerDirectory.findSellerOfRecord(sellerId).orElse(null);

            BigDecimal groupSubtotal = sellerLines.stream()
                    .map(PricedLine::lineTotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);

            // Free delivery is judged on the GOODS subtotal, not the total - otherwise
            // the delivery fee could push an order over its own free-delivery threshold,
            // which is a paradox a customer would (rightly) call a bug. Judged on THIS
            // seller's subtotal, matching the per-seller fee: an order that pays its own
            // fee must be able to earn its own exemption.
            BigDecimal groupDeliveryFee = groupSubtotal.compareTo(settings.getFreeDeliveryThreshold()) >= 0
                    ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                    : settings.getDeliveryFee().setScale(2, RoundingMode.HALF_UP);

            boolean platformOwner = seller != null && seller.isPlatformOwner();

            groups.add(new PricedSellerGroup(
                    sellerId,
                    seller == null ? "Unknown seller" : seller.getName(),
                    seller == null ? null : seller.getSlug(),
                    seller == null ? null : seller.getLogoUrl(),
                    platformOwner,
                    // Null for the platform owner: commission is a fact about a
                    // third-party sale, and ProcurePal is not a third party to itself.
                    platformOwner || seller == null ? null : seller.getCommissionRate(),
                    List.copyOf(sellerLines),
                    groupSubtotal,
                    groupDeliveryFee,
                    groupSubtotal.add(groupDeliveryFee).setScale(2, RoundingMode.HALF_UP)));
        }
        // Stable, human-meaningful ordering: the platform owner first, then sellers by
        // name. Checkout, the confirmation screen and the emailed receipt all render the
        // sub-orders in this order, and cart-insertion order would reshuffle them
        // whenever a line was removed.
        groups.sort(Comparator.comparing(PricedSellerGroup::platformOwner)
                .reversed()
                .thenComparing(group -> group.sellerName() == null ? "" : group.sellerName(),
                        String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(groups);
    }

    /**
     * The gates on pay-on-delivery. Returned as reasons rather than a boolean so
     * checkout can grey the option out and explain itself - "why can't I pay on
     * delivery" is otherwise a support call.
     *
     * <p>Three of them are the original ones (contract §5): the marketplace switch, the
     * buyer's payment terms, and the order-value ceiling. The fourth is new, and is a
     * deliberate v1 restriction rather than an oversight.
     *
     * <h2>Why a basket containing a vendor's goods may not be paid on delivery</h2>
     * Pay-on-delivery means ProcurePal's rider collects cash at the door. When the goods
     * are ProcurePal's own, that is simply a late payment to the seller - the same party
     * that already holds the money. When the goods are a THIRD PARTY's, the platform is
     * holding the vendor's money from the instant the rider takes it, and owes it onward.
     * That is a liability with a date on it, not a payment status: it has to be recorded
     * the day the cash is collected, tracked until it is remitted, and reversed if the
     * buyer returns the goods. VENDOR_RESEARCH.md Section C item 5 is explicit that
     * getting this wrong produces a cash-handling dispute rather than a bug.
     *
     * <p>The append-only vendor ledger that would record it belongs to a later module and
     * does not exist yet. Allowing vendor POD before it does would mean the platform
     * routinely holding other businesses' cash with no record of whose it is or how much
     * is owed - reconciled, if at all, from delivery notes. Refusing is the smaller
     * failure: the buyer pays by card or transfer, which they can do today, and the
     * vendor is paid through the normal settlement path.
     *
     * <p><b>Removal condition, so this does not become folklore:</b> delete this gate
     * when the vendor ledger can record a CASH_COLLECTED_ON_BEHALF liability at the
     * moment the rider settles up, and payouts net it off. Nothing else about this
     * decision needs revisiting - it is not a policy preference, it is a missing table.
     *
     * <p>ProcurePal's OWN orders are untouched by this. A basket with no vendor lines
     * behaves exactly as it did before the marketplace opened up, which is why the check
     * is on the basket's sellers and not on "is this a marketplace order".
     *
     * @param priced may be null for callers that only have a total (there are none
     *     today); a null basket is treated as containing no vendor goods, which is the
     *     pre-vendor behaviour.
     */
    public List<String> payOnDeliveryReasons(
            Client client, MarketplaceSettings settings, BigDecimal total, PricedCart priced) {
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
        if (priced != null && priced.hasVendorSeller()) {
            reasons.add("Pay on delivery is not available for items sold by our marketplace vendors. "
                    + "Please pay online to complete this order.");
        }
        return reasons;
    }

    /** Throws if the chosen method cannot be used for this basket. Called at order creation, not at quote. */
    public void requirePaymentMethodAllowed(PaymentMethod method, Client client, PricedCart priced) {
        if (method != PaymentMethod.PAY_ON_DELIVERY) {
            return;
        }
        List<String> reasons = payOnDeliveryReasons(client, priced.settings(), priced.total(), priced);
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
