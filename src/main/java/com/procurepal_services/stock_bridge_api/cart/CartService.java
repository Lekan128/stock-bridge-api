package com.procurepal_services.stock_bridge_api.cart;

import com.procurepal_services.stock_bridge_api.cart.dto.AddCartItemRequest;
import com.procurepal_services.stock_bridge_api.cart.dto.CartItemResponse;
import com.procurepal_services.stock_bridge_api.cart.dto.CartResponse;
import com.procurepal_services.stock_bridge_api.cart.dto.MergeCartRequest;
import com.procurepal_services.stock_bridge_api.entity.Cart;
import com.procurepal_services.stock_bridge_api.entity.CartItem;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.marketplace.BuyerCatalogLookup;
import com.procurepal_services.stock_bridge_api.order.CatalogStockService;
import com.procurepal_services.stock_bridge_api.repository.CartItemRepository;
import com.procurepal_services.stock_bridge_api.repository.CartRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The company's shared cart (contract §4.6, §8). One cart per client, never per
 * user: a storekeeper builds the requisition over the week and an owner checks it
 * out, so every line records who added it rather than living in its author's own
 * cart.
 *
 * <h2>The cross-tenant hop, which is where this feature goes wrong</h2>
 * cart_items.product_id points at ProcurePal's products while the request runs under
 * the BUYER's tenant filter. Every catalog read here therefore goes through
 * {@link BuyerCatalogLookup}, which loads by primary key (unfiltered) and re-checks
 * the platform owner's client_id in Java. A normal repository query would return
 * nothing and the symptom would be a cart that is silently, inexplicably empty.
 *
 * <h2>Prices</h2>
 * Nothing about a price is stored on a cart line. Both the response and checkout
 * read the live catalog row, so they can never disagree and a repricing is always
 * visible before the buyer commits.
 */
@Service
@RequiredArgsConstructor
public class CartService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final UserRepository userRepository;
    private final BuyerCatalogLookup buyerCatalogLookup;
    private final CatalogStockService catalogStockService;

    @Transactional
    public CartResponse getCart() {
        return render(findOrCreateCart());
    }

    @Transactional
    public CartResponse addItem(AddCartItemRequest request, UUID actingUserId) {
        Cart cart = findOrCreateCart();
        Product product = requirePurchasable(request.productId());
        int quantity = clampToMinimumOrderQuantity(request.quantity(), product);

        CartItem existing = cartItemRepository
                .findByCartIdAndProductId(cart.getId(), product.getId())
                .orElse(null);
        // Adding an item the cart already holds means "more of these", not "set to
        // this" - that is what the storefront's Add to cart button means when the
        // buyer presses it twice.
        int newQuantity = existing == null ? quantity : existing.getQuantity() + quantity;
        requireStock(product, newQuantity);

        if (existing == null) {
            cartItemRepository.save(CartItem.builder()
                    .cart(cart)
                    .productId(product.getId())
                    .quantity(newQuantity)
                    .addedBy(actingUserId == null ? null : userRepository.getReferenceById(actingUserId))
                    .build());
        } else {
            existing.setQuantity(newQuantity);
        }
        return render(cart);
    }

    @Transactional
    public CartResponse updateItemQuantity(UUID productId, int quantity, UUID actingUserId) {
        Cart cart = findOrCreateCart();
        CartItem item = cartItemRepository
                .findByCartIdAndProductId(cart.getId(), productId)
                .orElseThrow(CartItemNotFoundException::new);

        Product product = requirePurchasable(productId);
        int clamped = clampToMinimumOrderQuantity(quantity, product);
        requireStock(product, clamped);

        item.setQuantity(clamped);
        // Last toucher owns the line: a shared cart's audit line is only useful if it
        // names whoever last decided the quantity, not whoever first added it.
        if (actingUserId != null) {
            item.setAddedBy(userRepository.getReferenceById(actingUserId));
        }
        return render(cart);
    }

    @Transactional
    public CartResponse removeItem(UUID productId) {
        Cart cart = findOrCreateCart();
        cartItemRepository.deleteByCartIdAndProductId(cart.getId(), productId);
        cartItemRepository.flush();
        return render(cart);
    }

    @Transactional
    public CartResponse clear() {
        Cart cart = findOrCreateCart();
        clearItems(cart.getId());
        return render(cart);
    }

    /**
     * Empties the cart as part of a larger transaction (checkout). Split out from
     * {@link #clear()} so the order-placement path does not also pay for rendering a
     * cart nobody will read.
     */
    @Transactional
    public void clearItems(UUID cartId) {
        cartItemRepository.deleteByCartId(cartId);
        cartItemRepository.flush();
    }

    /**
     * Anonymous localStorage cart -> company cart on login (contract §8). Sums into
     * existing lines and skips anything no longer buyable, because this runs
     * automatically right after login: a stale local cart must never be able to make
     * signing in feel broken.
     */
    @Transactional
    public CartResponse merge(MergeCartRequest request, UUID actingUserId) {
        Cart cart = findOrCreateCart();
        for (AddCartItemRequest incoming : request.items()) {
            if (incoming == null || incoming.productId() == null || incoming.quantity() == null) {
                continue;
            }
            Product product = buyerCatalogLookup.findPurchasable(incoming.productId()).orElse(null);
            if (product == null) {
                continue;
            }
            CartItem existing = cartItemRepository
                    .findByCartIdAndProductId(cart.getId(), product.getId())
                    .orElse(null);
            int summed = (existing == null ? 0 : existing.getQuantity())
                    + Math.max(incoming.quantity(), 0);
            int sellable = catalogStockService.availableToSell(product);
            int quantity = Math.min(
                    clampToMinimumOrderQuantity(summed, product),
                    Math.max(sellable, product.getMinOrderQuantity()));
            if (existing == null) {
                cartItemRepository.save(CartItem.builder()
                        .cart(cart)
                        .productId(product.getId())
                        .quantity(quantity)
                        .addedBy(actingUserId == null ? null : userRepository.getReferenceById(actingUserId))
                        .build());
            } else {
                existing.setQuantity(quantity);
            }
        }
        return render(cart);
    }

    /**
     * Adds one line, tolerating anything that would normally be rejected by
     * returning false instead of throwing. Used by reorder, which must report what
     * it skipped rather than failing an entire basket because one product was
     * discontinued.
     */
    @Transactional
    public boolean tryAddLine(UUID productId, int requestedQuantity, UUID actingUserId) {
        Product product = buyerCatalogLookup.findPurchasable(productId).orElse(null);
        if (product == null) {
            return false;
        }
        int sellable = catalogStockService.availableToSell(product);
        if (sellable <= 0) {
            return false;
        }
        Cart cart = findOrCreateCart();
        CartItem existing = cartItemRepository
                .findByCartIdAndProductId(cart.getId(), product.getId())
                .orElse(null);
        int summed = (existing == null ? 0 : existing.getQuantity()) + Math.max(requestedQuantity, 1);
        int quantity = Math.min(clampToMinimumOrderQuantity(summed, product), sellable);
        if (existing == null) {
            cartItemRepository.save(CartItem.builder()
                    .cart(cart)
                    .productId(product.getId())
                    .quantity(quantity)
                    .addedBy(actingUserId == null ? null : userRepository.getReferenceById(actingUserId))
                    .build());
        } else {
            existing.setQuantity(quantity);
        }
        return true;
    }

    /**
     * Find-or-create rather than requiring an explicit "create cart" call: a cart is
     * an implementation detail of "this company has some items", and the unique
     * constraint on client_id means there is never a question of which one.
     */
    @Transactional
    public Cart findOrCreateCart() {
        UUID clientId = requireTenantId();
        return cartRepository
                .findByClientId(clientId)
                .orElseGet(() -> cartRepository.saveAndFlush(Cart.builder().build()));
    }

    @Transactional(readOnly = true)
    public List<CartItem> itemsOf(Cart cart) {
        return cartItemRepository.findAllByCartIdOrderByCreatedAtAsc(cart.getId());
    }

    @Transactional(readOnly = true)
    public CartResponse render(Cart cart) {
        List<CartItem> items = cartItemRepository.findAllByCartIdOrderByCreatedAtAsc(cart.getId());
        Map<UUID, Product> catalog = buyerCatalogLookup.findAllByIds(
                items.stream().map(CartItem::getProductId).toList());

        List<CartItemResponse> lines = new ArrayList<>(items.size());
        for (CartItem item : items) {
            Product product = catalog.get(item.getProductId());
            lines.add(CartItemResponse.of(
                    item, product, product == null ? 0 : catalogStockService.availableToSell(product)));
        }
        return CartResponse.of(cart.getId(), lines, cart.getUpdatedAt());
    }

    private Product requirePurchasable(UUID productId) {
        return buyerCatalogLookup.findPurchasable(productId).orElseThrow(CatalogProductUnavailableException::new);
    }

    /**
     * Measured against what is left to SELL, not against ProcurePal's raw
     * quantity_on_hand - that only falls at dispatch, so it still counts everything
     * sold today and not yet loaded. See CatalogStockService.
     */
    private void requireStock(Product product, int quantity) {
        int available = catalogStockService.availableToSell(product);
        if (available < quantity) {
            throw new InsufficientCatalogStockException(product.getName(), available);
        }
    }

    /**
     * MOQ is raised to, not rejected at. A wholesale MOQ is the seller's packing
     * unit, so "I want 1" from a buyer who has not read the fine print means "I want
     * the smallest amount you sell" - bouncing the request would just make them
     * retry with the number the UI already told them.
     */
    private static int clampToMinimumOrderQuantity(int requested, Product product) {
        return Math.max(requested, Math.max(product.getMinOrderQuantity(), 1));
    }

    private static UUID requireTenantId() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context set; cannot resolve a company cart");
        }
        return tenantId;
    }
}
