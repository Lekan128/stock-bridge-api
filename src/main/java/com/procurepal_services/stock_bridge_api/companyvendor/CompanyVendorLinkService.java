package com.procurepal_services.stock_bridge_api.companyvendor;

import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.CompanyVendor;
import com.procurepal_services.stock_bridge_api.entity.CompanyVendorKind;
import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.OrderItem;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.CompanyVendorRepository;
import com.procurepal_services.stock_bridge_api.repository.OrderItemRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantScopeExecutor;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place a VERIFIED directory entry is ever written: when a company's
 * marketplace order becomes real, the seller joins that company's vendor list.
 *
 * <h2>Why PLACED, and not checkout or delivery</h2>
 * {@link #recordPurchase} is called from {@code OrderLifecycleService.enterPlaced}
 * and nowhere else. That is the moment an order stops being a hopeful basket and
 * becomes a commitment - the money is either paid (a verified Monnify payment) or
 * promised on the doorstep (pay on delivery) - and it is the moment the buyer's
 * incoming stock appears. The three alternatives were each considered and each is
 * worse:
 *
 * <ul>
 *   <li><b>At checkout / order creation</b>: a Monnify order sits at
 *       PENDING_PAYMENT and may never be paid. A vendor would appear in the
 *       directory on the strength of an abandoned basket, which makes VERIFIED
 *       mean "we nearly bought from them" - and there is no honest way to remove
 *       it later, because a buyer may by then have written notes on the row.</li>
 *   <li><b>At payment</b>: not a separate moment. Applying a verified payment
 *       enters PLACED; hooking the payment path instead would only add a second
 *       way in and miss pay-on-delivery entirely, which is exactly the failure
 *       OrderLifecycleService's javadoc warns about.</li>
 *   <li><b>At delivery or receipt</b>: too late to be useful. The window in which a
 *       buyer most wants the supplier's number is while the goods are in transit
 *       and something has gone wrong with them. A directory that fills in
 *       afterwards is a record, not a tool.</li>
 * </ul>
 *
 * <h2>Why the integration is one call in OrderLifecycleService</h2>
 * An order reaches PLACED from two directions, and OrderLifecycleService exists
 * precisely so every consequence of that is written once rather than once per
 * caller. Hooking OrderService instead would cover the pay-on-delivery path and
 * silently miss the Monnify one. It also keeps this module clear of the
 * order-placement code being restructured for multi-seller checkout: when a
 * multi-seller cart splits into one Order per seller, each of those orders calls
 * enterPlaced on its own, so each gets its own VERIFIED row with no change here.
 *
 * <h2>Idempotency</h2>
 * Find-or-create on {@code (client_id, platform_client_id)}, which is what
 * {@link CompanyVendorRepository#findByClientIdAndPlatformClientId} exists for -
 * and which deliberately ignores {@code active}, so a company that removed a
 * supplier and then bought from them again gets their original row back rather
 * than a duplicate. A second order from the same seller therefore updates a row
 * instead of inserting one.
 *
 * <p>A genuinely simultaneous pair of first-ever orders from the same buyer to the
 * same seller would still collide on
 * {@code uq_company_vendors_client_id_platform_client_id}. That is left to fail
 * loudly rather than caught, on the same reasoning OrderNumberAllocator uses for
 * order numbers: the index is the real guard, and a caller retrying is better than
 * code that swallows a constraint violation - catching it here would mark the
 * order's own transaction rollback-only and turn a duplicate directory row into a
 * failed purchase. CompanyVendorExceptionHandler translates it into a 409 rather
 * than a 500 if it ever surfaces on a request thread.
 *
 * <h2>Tenant scope</h2>
 * The rows written belong to the BUYER, and the caller is frequently somebody
 * else - an unauthenticated Monnify webhook thread with no tenant at all, or
 * ProcurePal's own request. {@link TenantScopeExecutor} moves both TenantContext
 * and the Hibernate filter onto the buyer for the duration, which is what makes
 * {@code @PrePersist} stamp the right {@code client_id} and stops the find-or-create
 * query silently matching nothing and inserting a duplicate. Same reasoning, same
 * mechanism, as IncomingStockService.
 */
@Service
@RequiredArgsConstructor
public class CompanyVendorLinkService {

    private final CompanyVendorRepository companyVendorRepository;
    private final ClientRepository clientRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;
    private final TenantScopeExecutor tenantScopeExecutor;

    /**
     * Put this order's seller in the buyer's directory, and point the products the
     * order created at it. Called exactly once per order, on the transition into
     * PLACED.
     *
     * <p>Runs after IncomingStockService.materialize, and depends on it: that is
     * what creates the buyer's inventory rows and sets
     * {@link OrderItem#getBuyerProductId()}. Called before it, there would be
     * nothing to link.
     */
    @Transactional
    public void recordPurchase(Order order) {
        UUID buyerClientId = order.getClientId();
        UUID sellerClientId = order.getSellerClientId();
        if (sellerClientId == null || sellerClientId.equals(buyerClientId)) {
            // seller_client_id is NOT NULL in the schema, and a company selling to
            // itself is refused by chk_company_vendors_not_self. Neither should be
            // reachable; returning quietly rather than throwing is deliberate,
            // because the alternative is a data oddity failing a real purchase.
            return;
        }

        tenantScopeExecutor.runAs(buyerClientId, () -> {
            CompanyVendor vendor = findOrCreateVerifiedEntry(buyerClientId, sellerClientId);
            linkPurchasedProducts(order, vendor);
        });
    }

    private CompanyVendor findOrCreateVerifiedEntry(UUID buyerClientId, UUID sellerClientId) {
        String sellerName = sellerName(sellerClientId);

        CompanyVendor existing = companyVendorRepository
                .findByClientIdAndPlatformClientId(buyerClientId, sellerClientId)
                .orElse(null);
        if (existing != null) {
            // Buying from a supplier you had removed puts them back. The row carries
            // the buyer's own notes and the link from their products, so resurrecting
            // it is strictly better than starting a second one - and the partial
            // unique index would refuse the second one anyway.
            existing.setActive(true);
            // The snapshot refresh half of this module's answer to vendor renames -
            // see PlatformVendorSummary for the full ruling. An active trading
            // relationship keeps its name current for free; a dormant one does not,
            // and the detail screen covers that case by showing the live name.
            if (sellerName != null && !sellerName.equals(existing.getName())) {
                existing.setName(sellerName);
            }
            return companyVendorRepository.saveAndFlush(existing);
        }

        CompanyVendor created = CompanyVendor.builder()
                .vendorKind(CompanyVendorKind.VERIFIED)
                .platformClientId(sellerClientId)
                // Snapshotted rather than joined, because the directory list sorts,
                // searches and pages on it - see the CompanyVendor javadoc.
                .name(sellerName == null ? "ProcurePaddy seller" : sellerName)
                // No contactPhone: for a VERIFIED entry the authoritative number is
                // the seller's own clients.phone, and copying it here would create a
                // second copy to go stale. The detail screen reads it live.
                .active(true)
                .build();
        return companyVendorRepository.saveAndFlush(created);
    }

    /**
     * Point every product this order put into the buyer's inventory at the vendor
     * it came from, so "last purchase price" and "products supplied" have something
     * to hang on.
     *
     * <h2>An existing link is never overwritten</h2>
     * Only a null one is filled in. A buyer who deliberately filed a product under
     * their local miller must not have it silently re-pointed because they bought a
     * batch on the marketplace once - the link records the usual supplier, which is
     * a judgement, while the purchase history records what happened, which is a
     * fact. The fact is not lost: that order still appears in the new seller's
     * purchase history whatever the product is linked to.
     */
    private void linkPurchasedProducts(Order order, CompanyVendor vendor) {
        for (OrderItem item : orderItemRepository.findAllByOrderIdOrderByCreatedAtAsc(order.getId())) {
            resolveBuyerProduct(order.getClientId(), item)
                    .filter(product -> product.getCompanyVendor() == null)
                    .ifPresent(product -> product.setCompanyVendor(vendor));
        }
    }

    /**
     * The buyer's own inventory row for one order line.
     *
     * <p>{@code buyerProductId} first: IncomingStockService has just set it, and it
     * is the result of the same source_product_id-then-sku matching this would
     * otherwise repeat. The fallback covers the one case its javadoc admits is
     * possible - a line that reached here without ever being materialised - by
     * doing the source_product_id lookup directly, rather than leaving the product
     * unlinked for a reason nobody could later diagnose.
     */
    private java.util.Optional<Product> resolveBuyerProduct(UUID buyerClientId, OrderItem item) {
        if (item.getBuyerProductId() != null) {
            return productRepository.findByIdAndClientId(item.getBuyerProductId(), buyerClientId);
        }
        return productRepository.findByClientIdAndSourceProductId(buyerClientId, item.getProductId());
    }

    /**
     * The seller's live name. Loaded by primary key, which is not a cross-tenant
     * escape: Client is not a tenant-scoped entity and carries no filter to lift.
     * Null when the row has gone, which the caller turns into a neutral placeholder
     * rather than failing a purchase over a display string.
     */
    private String sellerName(UUID sellerClientId) {
        return clientRepository.findById(sellerClientId).map(Client::getName).orElse(null);
    }
}
