package com.procurepal_services.stock_bridge_api.marketplace.moderation;

import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.entity.ProductApprovalStatus;
import com.procurepal_services.stock_bridge_api.marketplace.SellerDirectory;
import com.procurepal_services.stock_bridge_api.marketplace.moderation.dto.ModerationProductResponse;
import com.procurepal_services.stock_bridge_api.marketplace.moderation.dto.ModerationQueueCountsResponse;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Listing moderation: the super admin's queue, the approve/reject decisions, and the
 * resubmission loop that follows a rejection.
 *
 * <h2>What moderation is for</h2>
 * A vendor's listing is a thing a real buying company will turn into a real purchase
 * order. VENDOR_RESEARCH.md Section C item 4 is blunt about the consequence of skipping
 * the gate, and Jumia holds new listings in QC for the same reason. This is the minimum
 * viable version that document asks for: a status on the product, a queue, a rejection
 * reason, and a way back in after a fix.
 *
 * <h2>The two rules that keep this correct</h2>
 * <ol>
 *   <li><b>Only sellers' products are moderated.</b> {@code approval_status} defaults to
 *       PENDING on every products row including every buying company's private
 *       inventory, so every read and every write here is pinned to the vendor-seller id
 *       set. See {@link ProductModerationSpecifications}, which explains the hazard, and
 *       {@link ProductModerationRules}, which states the obligation V11 left behind.</li>
 *   <li><b>ProcurePal does not moderate itself.</b> The platform owner is excluded from
 *       the queue and its products are stamped APPROVED at creation. A queue the
 *       operator has to clear before its own catalogue renders is a way to take the
 *       storefront down by going on holiday.</li>
 * </ol>
 *
 * <h2>No TenantContext at all</h2>
 * The caller is a super admin, and {@code SuperAdminPrincipal} is deliberately not a
 * TenantPrincipal - so the Hibernate tenant filter is off for the whole request and
 * these reads cross tenants by necessity. That is exactly the situation the public
 * catalog is in, and it is handled the same way: an explicit id-set predicate on every
 * query, never a bare {@code findById}, never {@code findAll()}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductModerationService {

    private final ProductRepository productRepository;
    private final SellerDirectory sellerDirectory;

    /**
     * The queue. Defaults to PENDING and to "the ones somebody is waiting on first":
     * listed before unlisted, then oldest first, because a moderation queue is a
     * first-in-first-out promise to vendors and sorting it any other way means the
     * awkward listings are never reached.
     */
    @Transactional(readOnly = true)
    public Page<ModerationProductResponse> queue(
            ProductApprovalStatus status, UUID sellerId, String query, Pageable pageable) {
        Set<UUID> vendorSellerIds = vendorSellerIds();
        if (vendorSellerIds.isEmpty()) {
            return Page.empty(pageable);
        }
        Pageable sorted = PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                Sort.by(Sort.Order.desc("marketplaceListed"), Sort.Order.asc("createdAt")));

        Map<UUID, Client> sellers = sellerDirectory.activeSellersById();
        return productRepository
                .findAll(ProductModerationSpecifications.queue(vendorSellerIds, status, sellerId, query), sorted)
                .map(product -> ModerationProductResponse.from(product, sellers.get(product.getClientId())));
    }

    /** One listing, for the reviewer's detail panel. 404s for anything not moderatable. */
    @Transactional(readOnly = true)
    public ModerationProductResponse get(UUID productId) {
        Product product = requireModeratable(productId);
        return ModerationProductResponse.from(
                product, sellerDirectory.findSellerOfRecord(product.getClientId()).orElse(null));
    }

    /** Tab badges. See {@link ModerationQueueCountsResponse} for why "awaiting" is not "pending". */
    @Transactional(readOnly = true)
    public ModerationQueueCountsResponse counts() {
        Set<UUID> vendorSellerIds = vendorSellerIds();
        if (vendorSellerIds.isEmpty()) {
            return new ModerationQueueCountsResponse(0, 0, 0, 0);
        }
        long pending = count(vendorSellerIds, ProductApprovalStatus.PENDING);
        long awaiting = productRepository.count(ProductModerationSpecifications
                .queue(vendorSellerIds, ProductApprovalStatus.PENDING, null, null)
                .and((root, cq, cb) -> cb.isTrue(root.get("marketplaceListed"))));
        return new ModerationQueueCountsResponse(
                awaiting,
                pending,
                count(vendorSellerIds, ProductApprovalStatus.APPROVED),
                count(vendorSellerIds, ProductApprovalStatus.REJECTED));
    }

    /**
     * Cleared for listing.
     *
     * <p>Does NOT set {@code marketplaceListed}. Approval is the platform saying "this
     * may be sold"; listing is the seller saying "sell it". Flipping the seller's own
     * flag on their behalf would publish a draft the vendor was still working on, and it
     * would make un-listing indistinguishable from being rejected. The two flags stay
     * separate and both are required - see MarketplaceProductSpecifications.
     *
     * <p>{@code rejectionReason} is deliberately KEPT rather than cleared, matching the
     * column comment in V11: the history of a contested listing is the useful part, and
     * a reviewer looking at a product that has been round the loop twice should be able
     * to see why.
     */
    @Transactional
    public ModerationProductResponse approve(UUID productId, UUID reviewerId) {
        Product product = requireModeratable(productId);
        product.setApprovalStatus(ProductApprovalStatus.APPROVED);
        product.setReviewedAt(OffsetDateTime.now());
        product.setReviewedBy(reviewerId);
        productRepository.save(product);

        log.info("Listing {} ({}) APPROVED by super admin {}", product.getSku(), product.getId(), reviewerId);
        return ModerationProductResponse.from(
                product, sellerDirectory.findSellerOfRecord(product.getClientId()).orElse(null));
    }

    /**
     * Refused, with a reason the vendor will see.
     *
     * <p>Does not unlist or deactivate the product either, for the same reason approve
     * does not list it: the catalog predicate already requires APPROVED, so a REJECTED
     * row is out of the public catalog the instant this commits regardless of what the
     * seller's own flags say. Leaving the seller's flags alone means that when the
     * vendor fixes the listing and it is approved, it returns to exactly the state they
     * had chosen - rather than silently coming back unlisted and being reported as "my
     * approved product isn't showing".
     */
    @Transactional
    public ModerationProductResponse reject(UUID productId, String reason, UUID reviewerId) {
        Product product = requireModeratable(productId);
        product.setApprovalStatus(ProductApprovalStatus.REJECTED);
        product.setRejectionReason(reason);
        product.setReviewedAt(OffsetDateTime.now());
        product.setReviewedBy(reviewerId);
        productRepository.save(product);

        log.info("Listing {} ({}) REJECTED by super admin {}", product.getSku(), product.getId(), reviewerId);
        return ModerationProductResponse.from(
                product, sellerDirectory.findSellerOfRecord(product.getClientId()).orElse(null));
    }

    /**
     * The resubmission half of the loop, called from the SELLER's own product edit path
     * rather than from a moderation endpoint.
     *
     * <p>That placement is the design: a vendor should not have to find a "resubmit"
     * button, because the thing that makes a rejected listing worth looking at again is
     * that it CHANGED. Editing it is the resubmission. Equally, editing an APPROVED
     * listing's identity fields drops it back to PENDING, which is what stops
     * approve-then-swap - see {@link ProductModerationRules} for the full ruling on
     * which fields count and why price and stock deliberately do not.
     *
     * @return true if the product's approval state was changed
     */
    @Transactional
    public boolean onListingContentChanged(Product product) {
        Client owner = sellerDirectory.findSellerOfRecord(product.getClientId()).orElse(null);
        if (!ProductModerationRules.isModerated(product, owner)) {
            // A buying company editing its own stock list, or ProcurePal editing its
            // catalogue. Neither is moderated; leave the column alone.
            return false;
        }
        if (product.getApprovalStatus() == ProductApprovalStatus.PENDING) {
            return false;
        }

        ProductApprovalStatus previous = product.getApprovalStatus();
        product.setApprovalStatus(ProductApprovalStatus.PENDING);
        // The decision is cleared because it no longer refers to this product: a
        // reviewer approved or refused something with a different name, image or
        // description. The rejection REASON survives, so a vendor fixing a rejection
        // can still read what they were told.
        product.setReviewedAt(null);
        product.setReviewedBy(null);

        log.info("Listing {} ({}) returned to PENDING from {} after a content edit",
                product.getSku(), product.getId(), previous);
        return true;
    }

    /**
     * Every active seller except the platform owner - who may be moderated.
     *
     * <p>Derived from {@link SellerDirectory} rather than assembled here so there is one
     * definition of "seller" in the application. The platform owner is removed for the
     * reason in the class javadoc; note this means a vendor who is DEACTIVATED drops out
     * of the queue, which is correct - their listings are already out of the catalog and
     * approving them would achieve nothing.
     */
    private Set<UUID> vendorSellerIds() {
        Set<UUID> ids = new LinkedHashSet<>();
        for (Client seller : sellerDirectory.activeSellers()) {
            if (!seller.isPlatformOwner()) {
                ids.add(seller.getId());
            }
        }
        return ids;
    }

    private long count(Set<UUID> vendorSellerIds, ProductApprovalStatus status) {
        return productRepository.count(
                ProductModerationSpecifications.queue(vendorSellerIds, status, null, null));
    }

    /**
     * Resolved through the pinned predicate, never a bare findById - so a request naming
     * a buying company's product id, or the platform owner's, 404s rather than letting
     * an operator stamp a decision on a row nobody moderates.
     */
    private Product requireModeratable(UUID productId) {
        return productRepository
                .findOne(ProductModerationSpecifications.moderatable(vendorSellerIds(), productId))
                .orElseThrow(ModeratedProductNotFoundException::new);
    }
}
