package com.procurepal_services.stock_bridge_api.companyvendor;

import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.CompanyVendor;
import com.procurepal_services.stock_bridge_api.entity.CompanyVendorKind;
import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.CompanyVendorRepository;
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
 *
 * <h2>V19: this class no longer links products to the vendor</h2>
 * Before V19, {@code recordPurchase} also pointed every product an order created at the
 * VERIFIED entry it found-or-created, via the single {@code products.company_vendor_id} FK. That
 * FK is gone - a product may now have many vendors (see {@code ProductVendor}) - and the
 * corresponding {@code product_vendors} row is created lazily, at RECEIPT rather than at PLACED,
 * by {@code companyvendor.ProductVendorService.findOrCreateForReceipt} through
 * {@code order.IncomingStockService.receive}. That is a deliberate move, not an oversight: goods
 * that are still merely PLACED (paid for or promised, not yet in hand) have not actually arrived
 * from this vendor yet, so recording a vendor LINE - with a cost, a packaging default, a
 * received quantity - before receipt would be asserting a delivery that has not happened. This
 * class's remaining job is exactly what its own name says: making sure the VERIFIED {@link
 * CompanyVendor} entry itself exists, via {@link #findOrCreateVerifiedEntry}, which
 * {@code IncomingStockService.receive} calls directly.
 */
@Service
@RequiredArgsConstructor
public class CompanyVendorLinkService {

    private final CompanyVendorRepository companyVendorRepository;
    private final ClientRepository clientRepository;
    private final TenantScopeExecutor tenantScopeExecutor;

    /**
     * Put this order's seller in the buyer's directory. Called exactly once per order, on the
     * transition into PLACED - see the class javadoc for why product-vendor linking itself no
     * longer happens here.
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

        tenantScopeExecutor.runAs(buyerClientId, () -> findOrCreateVerifiedEntry(buyerClientId, sellerClientId));
    }

    /**
     * Idempotent find-or-create of this buyer's VERIFIED directory entry for a seller - see the
     * class javadoc's "Idempotency" section. Public since V19: {@code
     * IncomingStockService.receive} calls this directly (already running inside the buyer's
     * {@link TenantScopeExecutor} scope) to resolve the {@code companyVendorId} a receipt needs
     * to pass into {@code StockManagementService.stockIn}, rather than re-deriving the seller's
     * identity a second way.
     */
    public CompanyVendor findOrCreateVerifiedEntry(UUID buyerClientId, UUID sellerClientId) {
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
     * The seller's live name. Loaded by primary key, which is not a cross-tenant
     * escape: Client is not a tenant-scoped entity and carries no filter to lift.
     * Null when the row has gone, which the caller turns into a neutral placeholder
     * rather than failing a purchase over a display string.
     */
    private String sellerName(UUID sellerClientId) {
        return clientRepository.findById(sellerClientId).map(Client::getName).orElse(null);
    }
}
