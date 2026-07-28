package com.procurepal_services.stock_bridge_api.order;

import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.OrderItem;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.marketplace.BuyerCatalogLookup;
import com.procurepal_services.stock_bridge_api.repository.OrderItemRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.stock.StockManagementService;
import com.procurepal_services.stock_bridge_api.stock.dto.StockInRequest;
import com.procurepal_services.stock_bridge_api.tenant.TenantScopeExecutor;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The behaviour the whole marketplace exists to produce, in the user's own words:
 * "it should show in their inventory but not as what they can use, but as something
 * like pending delivery... after payment is successful."
 *
 * <h2>Three moments, and what happens at each</h2>
 * <ol>
 *   <li><b>Into PLACED</b> (Monnify verified, or a COD order placed):
 *       {@code incoming_quantity += quantity} on the buyer's own product row,
 *       creating that row from the catalog product if this is the first time they
 *       have bought it. <b>No StockMovement.</b> Nothing has physically moved, and
 *       an IN movement here would both inflate quantity_on_hand and put a lie in an
 *       append-only ledger.</li>
 *   <li><b>Receipt</b> (buyer confirms, possibly partially): {@code incoming_quantity -=
 *       receivedQty} and a real IN {@link com.procurepal_services.stock_bridge_api.entity.StockMovement}
 *       through {@link StockManagementService}, priced at what was actually paid. This is the
 *       moment stock becomes usable.</li>
 *   <li><b>Cancellation</b> at or after PLACED: the un-received remainder of
 *       incoming_quantity is given back. Anything already received stays - it is real
 *       stock in a real store and a cancellation cannot un-deliver it.</li>
 * </ol>
 *
 * <h2>Two things that make this correct rather than merely working</h2>
 * <b>Locking.</b> Every mutation of incoming_quantity goes through
 * {@code ProductRepository.findByIdForUpdate}, so a duplicated webhook and a
 * concurrent receipt serialise on the row instead of racing on a read-modify-write.
 * Order-level idempotency (the status transition guard in OrderLifecycleService) is
 * what stops a re-delivered webhook applying twice; the row lock is what stops two
 * simultaneous ones interleaving.
 *
 * <b>Tenant scope.</b> These rows belong to the BUYER, and the caller is frequently
 * somebody else - an unauthenticated webhook thread with no tenant at all, or
 * ProcurePal cancelling from its own tenant. {@link TenantScopeExecutor} moves both
 * TenantContext and the Hibernate filter onto the buyer for the duration, which is
 * what makes {@code @PrePersist} stamp the right client_id and what stops the
 * queries silently matching nothing.
 */
@Service
@RequiredArgsConstructor
public class IncomingStockService {

    private final ProductRepository productRepository;
    private final OrderItemRepository orderItemRepository;
    private final BuyerCatalogLookup buyerCatalogLookup;
    private final StockManagementService stockManagementService;
    private final TenantScopeExecutor tenantScopeExecutor;

    /**
     * Called exactly once per order, on the transition into PLACED. Callers must
     * enforce that; this method does not guess, because "has it already run" is a
     * property of the order's status, not of the products it touched.
     */
    @Transactional
    public void materialize(Order order) {
        tenantScopeExecutor.runAs(order.getClientId(), () -> {
            for (OrderItem item : orderItemRepository.findAllByOrderIdOrderByCreatedAtAsc(order.getId())) {
                Product buyerProduct = findOrCreateBuyerProduct(order.getClientId(), item);
                Product locked = productRepository
                        .findByIdForUpdate(buyerProduct.getId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Buyer product vanished while applying incoming stock: " + buyerProduct.getId()));
                locked.setIncomingQuantity(locked.getIncomingQuantity() + item.getQuantity());
                item.setBuyerProductId(locked.getId());
            }
            orderItemRepository.flush();
        });
    }

    /**
     * Turns the received portion of one line into real stock. Returns the quantity
     * actually applied, which is clamped to what is still outstanding - a
     * fat-fingered "received 500" must not create stock out of nothing.
     */
    @Transactional
    public int receive(Order order, OrderItem item, int requestedQuantity, UUID actingUserId) {
        int quantity = Math.min(requestedQuantity, item.outstandingQuantity());
        if (quantity <= 0) {
            return 0;
        }
        return tenantScopeExecutor.callAs(order.getClientId(), () -> {
            UUID buyerProductId = item.getBuyerProductId();
            if (buyerProductId == null) {
                // Can only happen if the order reached DELIVERED without ever passing
                // through PLACED, which the state machine forbids. Repair rather than
                // fail: the buyer is standing in front of the goods.
                buyerProductId = findOrCreateBuyerProduct(order.getClientId(), item).getId();
                item.setBuyerProductId(buyerProductId);
            }

            Product locked = productRepository
                    .findByIdForUpdate(buyerProductId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Buyer product vanished while receiving an order line: " + item.getId()));
            // Floor at zero rather than trusting the arithmetic: chk_products_incoming_non_negative
            // would otherwise turn a data inconsistency into a failed delivery confirmation,
            // and the buyer cannot fix that from where they are standing.
            locked.setIncomingQuantity(Math.max(0, locked.getIncomingQuantity() - quantity));

            // Reused rather than reimplemented: StockManagementService owns the ledger
            // write, the row lock and the quantity_on_hand update, and a second copy of
            // that logic here would be the one that drifts.
            stockManagementService.stockIn(
                    locked.getId(),
                    new StockInRequest(
                            quantity,
                            item.getUnitPrice(),
                            "Received from marketplace order " + order.getOrderNumber()),
                    actingUserId);

            item.setReceivedQuantity(item.getReceivedQuantity() + quantity);
            return quantity;
        });
    }

    /**
     * Gives back the incoming quantity a cancelled order had reserved. Only the
     * un-received remainder: partially received goods are already on hand and stay
     * there.
     */
    @Transactional
    public void reverse(Order order) {
        tenantScopeExecutor.runAs(order.getClientId(), () -> {
            for (OrderItem item : orderItemRepository.findAllByOrderIdOrderByCreatedAtAsc(order.getId())) {
                int outstanding = item.outstandingQuantity();
                if (outstanding <= 0 || item.getBuyerProductId() == null) {
                    continue;
                }
                productRepository.findByIdForUpdate(item.getBuyerProductId()).ifPresent(product ->
                        product.setIncomingQuantity(Math.max(0, product.getIncomingQuantity() - outstanding)));
            }
        });
    }

    /**
     * Match, then match again, then create.
     *
     * <ol>
     *   <li>{@code source_product_id} - the strong link, set the first time this buyer
     *       bought this catalog product. Survives the buyer renaming or re-SKU'ing
     *       their copy, which is why it is tried first.</li>
     *   <li>{@code sku} within the buyer's own catalog - covers a buyer who was already
     *       tracking the item before they bought it here. Back-fills
     *       source_product_id so step 1 works next time.</li>
     *   <li>Create it, at {@code quantity_on_hand = 0}: they own none of it yet, only
     *       incoming.</li>
     * </ol>
     */
    private Product findOrCreateBuyerProduct(UUID buyerClientId, OrderItem item) {
        Product existing = productRepository
                .findByClientIdAndSourceProductId(buyerClientId, item.getProductId())
                .orElse(null);
        if (existing != null) {
            return existing;
        }

        Product bySku = productRepository
                .findByClientIdAndSku(buyerClientId, item.getProductSku())
                .orElse(null);
        if (bySku != null) {
            if (bySku.getSourceProductId() == null) {
                bySku.setSourceProductId(item.getProductId());
            }
            return bySku;
        }

        Product catalogProduct = buyerCatalogLookup.findAnyCatalogProduct(item.getProductId()).orElse(null);
        Product created = Product.builder()
                .name(item.getProductName())
                .sku(item.getProductSku())
                .description(catalogProduct == null ? null : catalogProduct.getDescription())
                // Priced at what they paid: it is their cost, and it is the only price
                // the system can honestly assert about their copy of the item. They are
                // free to reprice it for their own selling.
                .unitPrice(item.getUnitPrice())
                .costPrice(item.getUnitPrice())
                .quantityOnHand(0)
                .incomingQuantity(0)
                .imageUrl(item.getImageUrl())
                .active(true)
                // Never listed. A buyer's inventory row is not merchandise on the
                // marketplace - only the platform owner's products can ever be listed.
                .marketplaceListed(false)
                .unitOfMeasure(item.getUnitOfMeasure())
                .minOrderQuantity(1)
                .brand(catalogProduct == null ? null : catalogProduct.getBrand())
                .category(catalogProduct == null ? null : catalogProduct.getCategory())
                // No slug: slugs are unique per client and only mean anything on the
                // public storefront, which this row will never appear on.
                .sourceProductId(item.getProductId())
                .build();
        return productRepository.saveAndFlush(created);
    }

    @Transactional(readOnly = true)
    public List<OrderItem> itemsOf(Order order) {
        return orderItemRepository.findAllByOrderIdOrderByCreatedAtAsc(order.getId());
    }
}
