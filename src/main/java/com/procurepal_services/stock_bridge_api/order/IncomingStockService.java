package com.procurepal_services.stock_bridge_api.order;

import com.procurepal_services.stock_bridge_api.companyvendor.CompanyVendorLinkService;
import com.procurepal_services.stock_bridge_api.entity.CompanyVendor;
import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.OrderItem;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.imports.NameSimilarity;
import com.procurepal_services.stock_bridge_api.marketplace.BuyerCatalogLookup;
import com.procurepal_services.stock_bridge_api.order.dto.OrderItemMatchSuggestionResponse;
import com.procurepal_services.stock_bridge_api.order.dto.OrderItemMatchSuggestionResponse.ProductMatchCandidateResponse;
import com.procurepal_services.stock_bridge_api.product.ProductNotFoundException;
import com.procurepal_services.stock_bridge_api.product.sku.ProductSkuSettingsService;
import com.procurepal_services.stock_bridge_api.product.sku.SkuGenerationService;
import com.procurepal_services.stock_bridge_api.repository.OrderItemRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.stock.StockManagementService;
import com.procurepal_services.stock_bridge_api.stock.dto.StockInRequest;
import com.procurepal_services.stock_bridge_api.tenant.TenantScopeExecutor;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
     * V19: resolves the VERIFIED {@link CompanyVendor} entry for this order's seller, so {@link
     * #receive} can pass a {@code companyVendorId} into {@code StockManagementService.stockIn} -
     * which is what actually creates/reuses the {@code ProductVendor} line for this delivery
     * (see design doc section 7.2). Reused rather than re-derived: {@link
     * CompanyVendorLinkService#findOrCreateVerifiedEntry} is already the one place that logic
     * lives, called from {@code recordPurchase} at PLACED - by the time a receipt happens the
     * row should already exist, but the call is idempotent either way.
     */
    private final CompanyVendorLinkService companyVendorLinkService;
    /**
     * A buyer's own SKU scheme, if they have one configured - see {@link #matchOrCreateBuyerProduct}.
     * Without these, a marketplace-created row's {@code Product.sku} used to be a straight copy of
     * the SELLER's catalog SKU, which is a different tenant's identifier wearing this one's field:
     * it bypasses whatever numbering the buyer set up for every product they add by hand, and two
     * different sellers who happen to reuse the same code would collide in
     * {@code findByClientIdAndSku}'s match step. The seller's code is not discarded - it moves to
     * {@code ProductVendorPack.vendorSku} instead, via {@link #receive}'s {@code vendorSku} on the
     * stock-in it writes, which is where "the supplier's own code for this item" already lives for
     * every other vendor pairing (MULTI_VENDOR_INVENTORY_DESIGN.md section 4a).
     */
    private final ProductSkuSettingsService productSkuSettingsService;
    private final SkuGenerationService skuGenerationService;

    /**
     * Called exactly once per order, on the transition into PLACED. Callers must
     * enforce that; this method does not guess, because "has it already run" is a
     * property of the order's status, not of the products it touched.
     */
    @Transactional
    public void materialize(Order order) {
        tenantScopeExecutor.runAs(order.getClientId(), () -> {
            for (OrderItem item : orderItemRepository.findAllByOrderIdOrderByCreatedAtAsc(order.getId())) {
                BuyerProductMatch match = matchOrCreateBuyerProduct(order.getClientId(), item);
                Product locked = productRepository
                        .findByIdForUpdate(match.product().getId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Buyer product vanished while applying incoming stock: " + match.product().getId()));
                locked.setIncomingQuantity(locked.getIncomingQuantity() + item.getQuantity());
                item.setBuyerProductId(locked.getId());
                item.setBuyerProductNewlyCreated(match.created());
            }
            orderItemRepository.flush();
        });
    }

    /**
     * Turns the received portion of one line into real stock. Returns the quantity
     * actually applied, which is clamped to what is still outstanding - a
     * fat-fingered "received 500" must not create stock out of nothing.
     *
     * <p>{@code linkToExistingProductId} is the buyer answering the MULTI_VENDOR_INVENTORY_DESIGN.md
     * section 7.2 duplicate nudge with "yes, same item" - see {@link #suggestMatches} for where
     * the candidates it is chosen from come from. Honoured only when the line's buyer product was
     * itself freshly created (nothing to redirect if it already matched) and nothing has been
     * received against it yet (once a StockMovement exists, redirecting the reservation would mean
     * moving ledger history, which section 2 of the design doc rules out as the risky path - null
     * or ineligible is silently a no-op, never an error, since most receipts pass null).
     *
     * <p>{@code packagingUnit}/{@code packagingSize} answer the follow-up a relink can raise when
     * the order line's unit is not one the chosen product already accepts: the buyer's own "1 of
     * this = N of that" conversion, extending the target's unit set for this receipt exactly the
     * way a manual stock-in's per-delivery pack override does. {@code saveAsSupplierDefault} keeps
     * it for next time. All three are ignored outside the relink case, same as
     * {@code linkToExistingProductId} itself.
     */
    @Transactional
    public int receive(
            Order order,
            OrderItem item,
            int requestedQuantity,
            UUID actingUserId,
            UUID linkToExistingProductId,
            String packagingUnit,
            BigDecimal packagingSize,
            boolean saveAsSupplierDefault) {
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
                BuyerProductMatch match = matchOrCreateBuyerProduct(order.getClientId(), item);
                buyerProductId = match.product().getId();
                item.setBuyerProductId(buyerProductId);
                item.setBuyerProductNewlyCreated(match.created());
            }

            Product locked = productRepository
                    .findByIdForUpdate(buyerProductId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Buyer product vanished while receiving an order line: " + item.getId()));

            // The buyer saying "actually, that's the same item as one I already have" - move the
            // whole outstanding reservation onto the product they picked instead of the one
            // findOrCreateBuyerProduct guessed at, and remember the choice (sourceProductId,
            // buyerProductId) so re-ordering the same catalog item never asks again.
            //
            // The order line's quantity is in the SELLER's catalog product's stock unit
            // (item.getUnitOfMeasure()), which - unlike the sourceProductId/SKU matches above -
            // is not guaranteed to be the buyer-chosen target's: two sellers of "the same" real
            // item may track it differently (bags vs kg). Every quantity crossing onto target is
            // therefore converted through StockManagementService's own unit resolution rather
            // than copied raw, so this relink cannot silently write the wrong number into
            // someone's stock count - an unconvertible unit throws InvalidStockUnitException
            // instead (see OrderExceptionHandler), which is the correct outcome: fix the pack on
            // the target product, or answer the nudge with "no, it's new" instead.
            Product orphanedDuplicate = null;
            int quantityInLockedUnit = quantity;
            // Non-null only on a relink, and then it is passed to stockIn below alongside the
            // RAW (unconverted) quantity and price - never a pre-converted quantity next to an
            // unconverted price. That pairing is exactly V21's fixed bug (P0-1): quantity and
            // price must be scaled by the same factor in the same call, or the ledger silently
            // prices a kg delivery as if it were a bag one.
            String stockInUnit = null;
            if (linkToExistingProductId != null
                    && item.isBuyerProductNewlyCreated()
                    && item.getReceivedQuantity() == 0
                    && !linkToExistingProductId.equals(locked.getId())) {
                Product target = productRepository
                        .findByIdForUpdate(linkToExistingProductId)
                        .orElseThrow(ProductNotFoundException::new);
                stockInUnit = item.getUnitOfMeasure();
                // incomingQuantity carries no price, so converting it here (rather than deferring
                // to stockIn, which never touches incomingQuantity at all) is safe on its own -
                // the quantity/price pairing rule above only binds where both travel together.
                // packagingUnit/packagingSize extend target's unit set identically for both this
                // bookkeeping conversion and the ledger write below, so a buyer-supplied "1 bag =
                // 25 kg" answer resolves the SAME way in both places.
                int outstandingInTargetUnit = stockManagementService.toStockUnitQuantity(
                        target, item.getQuantity(), item.getUnitOfMeasure(), packagingUnit, packagingSize);
                quantityInLockedUnit = stockManagementService.toStockUnitQuantity(
                        target, quantity, stockInUnit, packagingUnit, packagingSize);
                locked.setIncomingQuantity(Math.max(0, locked.getIncomingQuantity() - item.getQuantity()));
                target.setIncomingQuantity(target.getIncomingQuantity() + outstandingInTargetUnit);
                if (target.getSourceProductId() == null) {
                    target.setSourceProductId(item.getProductId());
                }
                item.setBuyerProductId(target.getId());
                item.setBuyerProductNewlyCreated(false);
                orphanedDuplicate = locked;
                locked = target;
            }

            // Floor at zero rather than trusting the arithmetic: chk_products_incoming_non_negative
            // would otherwise turn a data inconsistency into a failed delivery confirmation,
            // and the buyer cannot fix that from where they are standing.
            locked.setIncomingQuantity(Math.max(0, locked.getIncomingQuantity() - quantityInLockedUnit));

            // V19: the vendor side of a verified-vendor receipt is no longer silent - see the
            // companyVendorLinkService field javadoc and design doc section 7.2. sellerClientId
            // is guarded the same way CompanyVendorLinkService.recordPurchase guards it: both
            // should be unreachable in practice (seller_client_id is NOT NULL and an order
            // cannot name its own buyer as seller), but a receipt must not fail outright over a
            // data oddity when the buyer is standing in front of the goods - it simply proceeds
            // with no vendor attributed, exactly as a manual off-platform stock-in with no
            // vendor picked would.
            UUID companyVendorId = null;
            if (order.getSellerClientId() != null && !order.getSellerClientId().equals(order.getClientId())) {
                CompanyVendor verifiedVendor =
                        companyVendorLinkService.findOrCreateVerifiedEntry(order.getClientId(), order.getSellerClientId());
                companyVendorId = verifiedVendor.getId();
            }

            // Reused rather than reimplemented: StockManagementService owns the ledger
            // write, the row lock, the weighted-average cost recalculation and the
            // quantity_on_hand/ProductVendor updates, and a second copy of that logic here
            // would be the one that drifts. RAW quantity and RAW price go in together with
            // stockInUnit (null outside a relink, item.getUnitOfMeasure() inside one) so
            // resolveEntry converts both by the same factor - see the note above on why this
            // must never be quantityInLockedUnit paired with an unconverted price. packagingUnit/
            // packagingSize/saveAsSupplierDefault are the buyer's own conversion answer when
            // stockInUnit wasn't already one of the target's units - null/false outside a relink,
            // same as stockInUnit itself.
            //
            // vendorSku is the seller's own code for this catalog item - never touching
            // Product.sku (see matchOrCreateBuyerProduct) - so it lands where every other
            // vendor's code does, ProductVendorPack.vendorSku.
            stockManagementService.stockIn(
                    locked.getId(),
                    new StockInRequest(
                            quantity,
                            item.getUnitPrice(),
                            "Received from marketplace order " + order.getOrderNumber(),
                            stockInUnit,
                            companyVendorId,
                            packagingUnit,
                            packagingSize,
                            null,
                            saveAsSupplierDefault,
                            item.getProductSku()),
                    actingUserId);

            item.setReceivedQuantity(item.getReceivedQuantity() + quantity);

            // The row the relink above just abandoned was never anything but this one
            // reservation - if nothing else ever touched it, leaving it behind would be
            // exactly the empty duplicate row the buyer was trying to avoid by relinking.
            // flush() first: existsByBuyerProductId must see item's new buyerProductId, set
            // above, not the one it still carried before this method ran.
            if (orphanedDuplicate != null) {
                orderItemRepository.flush();
                if (orphanedDuplicate.getQuantityOnHand() == 0
                        && orphanedDuplicate.getIncomingQuantity() == 0
                        && !orderItemRepository.existsByBuyerProductId(orphanedDuplicate.getId())) {
                    productRepository.delete(orphanedDuplicate);
                }
            }

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

    /** {@code created} is what feeds {@code OrderItem.buyerProductNewlyCreated} - see its javadoc. */
    private record BuyerProductMatch(Product product, boolean created) {
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
     *
     * Neither match step covers *the same real-world product bought from a different seller* -
     * Vendor A's and Vendor B's listings have different {@code sourceProductId}s and typically
     * different SKUs, so that case still falls through to create - which is exactly the gap
     * {@link #suggestMatches} and the {@code linkToExistingProductId} branch of {@link #receive}
     * exist to let the buyer close by hand (MULTI_VENDOR_INVENTORY_DESIGN.md section 7.2).
     */
    private BuyerProductMatch matchOrCreateBuyerProduct(UUID buyerClientId, OrderItem item) {
        Product existing = productRepository
                .findByClientIdAndSourceProductId(buyerClientId, item.getProductId())
                .orElse(null);
        if (existing != null) {
            return new BuyerProductMatch(existing, false);
        }

        Product bySku = productRepository
                .findByClientIdAndSku(buyerClientId, item.getProductSku())
                .orElse(null);
        if (bySku != null) {
            if (bySku.getSourceProductId() == null) {
                bySku.setSourceProductId(item.getProductId());
            }
            return new BuyerProductMatch(bySku, false);
        }

        Product catalogProduct = buyerCatalogLookup.findAnyCatalogProduct(item.getProductId()).orElse(null);
        // The buyer's OWN identifier for their OWN inventory row - never the seller's SKU, which
        // is a different tenant's scheme and is preserved separately as ProductVendorPack
        // .vendorSku instead (see the field javadoc above). Generated exactly as
        // ProductManagementService.create() would for a product this buyer added by hand,
        // because from the buyer's own catalog's point of view that is exactly what this is.
        // Only when SKU generation is off - meaning this tenant has no scheme of its own to
        // apply - is there no better identifier than borrowing the seller's, so that fallback is
        // kept rather than inventing a third convention nobody asked for.
        String buyerSku = productSkuSettingsService.isEnabled(buyerClientId)
                ? skuGenerationService.generateAndReserveOne(buyerClientId, item.getProductName())
                : item.getProductSku();
        Product created = Product.builder()
                .name(item.getProductName())
                .sku(buyerSku)
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
        return new BuyerProductMatch(productRepository.saveAndFlush(created), true);
    }

    @Transactional(readOnly = true)
    public List<OrderItem> itemsOf(Order order) {
        return orderItemRepository.findAllByOrderIdOrderByCreatedAtAsc(order.getId());
    }

    /**
     * The MULTI_VENDOR_INVENTORY_DESIGN.md section 7.2 duplicate nudge: for each outstanding
     * line whose buyer product was freshly created (no source-product or SKU match at PLACED -
     * see {@link #matchOrCreateBuyerProduct}) and nothing has been received against it yet, other
     * active products already in the buyer's own inventory whose name looks like the same
     * real-world item. A line with a clean match, or one already partially received, has nothing
     * to ask and is simply omitted - "no prompt at all" is itself the correct answer there, not
     * an empty list to render.
     */
    @Transactional(readOnly = true)
    public List<OrderItemMatchSuggestionResponse> suggestMatches(Order order) {
        return tenantScopeExecutor.callAs(order.getClientId(), () -> {
            List<Product> catalogue =
                    productRepository.findAllByClientIdAndActiveTrueOrderByNameAsc(order.getClientId());
            List<OrderItem> items = orderItemRepository.findAllByOrderIdOrderByCreatedAtAsc(order.getId());
            // Every product THIS order brought into existence. None of them answers the question
            // the nudge asks - "is this already in your inventory?" - because none of them was,
            // until materialize() created it for this very order seconds ago.
            //
            // Without this, a two-line order of two different goods nudged each line towards the
            // OTHER line's brand-new row ("Bags Of Rice ... might already be in your inventory as
            // Dangote Parboiled Rice 50kg (0 on hand)"), and answering "yes, same item" would
            // relink one line onto the other's product and then delete the row it abandoned -
            // collapsing two genuinely different items into one, on the buyer's say-so, in
            // response to a question they were wrong to be asked.
            //
            // Scoped to this order rather than to "created recently": a row an EARLIER order
            // created is a real part of the buyer's inventory by now and is a legitimate
            // candidate, which is exactly the repeat-purchase case section 7.2 exists for.
            Set<UUID> createdByThisOrder = items.stream()
                    .filter(OrderItem::isBuyerProductNewlyCreated)
                    .map(OrderItem::getBuyerProductId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            List<OrderItemMatchSuggestionResponse> suggestions = new ArrayList<>();
            for (OrderItem item : items) {
                if (!item.isBuyerProductNewlyCreated() || item.getReceivedQuantity() > 0) {
                    continue;
                }
                List<Product> candidates = catalogue.stream()
                        .filter(p -> !createdByThisOrder.contains(p.getId()))
                        .filter(p -> NameSimilarity.score(item.getProductName(), p.getName())
                                >= NameSimilarity.SUGGESTION_FLOOR)
                        .sorted(Comparator.comparingDouble(
                                        (Product p) -> NameSimilarity.score(item.getProductName(), p.getName()))
                                .reversed())
                        .limit(3)
                        .toList();
                if (!candidates.isEmpty()) {
                    suggestions.add(new OrderItemMatchSuggestionResponse(
                            item.getId(), candidates.stream().map(ProductMatchCandidateResponse::from).toList()));
                }
            }
            return suggestions;
        });
    }
}
