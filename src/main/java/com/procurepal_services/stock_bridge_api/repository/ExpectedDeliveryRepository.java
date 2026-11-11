package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.ExpectedDelivery;
import com.procurepal_services.stock_bridge_api.entity.ExpectedDeliveryStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ExpectedDeliveryRepository extends JpaRepository<ExpectedDelivery, UUID> {

    Page<ExpectedDelivery> findAllByClientIdOrderByCreatedAtDesc(UUID clientId, Pageable pageable);

    Page<ExpectedDelivery> findAllByClientIdAndStatusOrderByCreatedAtDesc(
            UUID clientId, ExpectedDeliveryStatus status, Pageable pageable);

    /** Multiple branches: the ones going to these branches (the person's scope). */
    Page<ExpectedDelivery> findAllByClientIdAndBranchIdInOrderByCreatedAtDesc(
            UUID clientId, java.util.Collection<UUID> branchIds, Pageable pageable);

    Page<ExpectedDelivery> findAllByClientIdAndStatusAndBranchIdInOrderByCreatedAtDesc(
            UUID clientId, ExpectedDeliveryStatus status, java.util.Collection<UUID> branchIds, Pageable pageable);

    /**
     * Read by id AND client, never by id alone: layer 2 of the tenant isolation
     * {@code TenantAwareEntity} documents, which holds even if the Hibernate filter is ever left
     * disabled.
     */
    Optional<ExpectedDelivery> findByIdAndClientId(UUID id, UUID clientId);

    /**
     * The public link's lookup. Runs with no tenant (the supplier has no login), so it is the token
     * alone that scopes it - which is why the token is a long random string and nothing else.
     */
    Optional<ExpectedDelivery> findByShareToken(String shareToken);

    /**
     * Every still-owed line for these products, as rows of {productId, unit, outstanding}.
     * One query for a whole page - the same N+1 the batched preferred-vendor read avoids.
     *
     * <p>Only OPEN expectations count, and only the part not yet received. A line that has arrived
     * in full contributes nothing, and an over-delivery contributes nothing rather than a negative
     * that would quietly cancel out another line's genuine shortfall.
     *
     * <p><b>Deliberately not summed here.</b> Each line is counted in the unit it was ordered in,
     * so ten 50 kg bags and five loose kg are 10 and 5 - and adding those gives 15, which is not a
     * quantity of anything. The rows come back per unit and the service converts each to the
     * product's stock unit before adding, the same rule the review grid's "never sum across stock
     * units" line follows.
     */
    @Query("select l.product.id, l.unit, sum(l.quantity - l.receivedQuantity) from ExpectedDeliveryLine l "
            + "where l.expectedDelivery.clientId = :clientId "
            + "and l.expectedDelivery.status = com.procurepal_services.stock_bridge_api.entity.ExpectedDeliveryStatus.OPEN "
            + "and l.quantity > l.receivedQuantity "
            + "and l.product.id in :productIds "
            + "group by l.product.id, l.unit")
    List<Object[]> outstandingByProductAndUnit(UUID clientId, List<UUID> productIds);

    /** The shared WHERE of the list's search and its counts (redesign 2026-11-04). */
    String LIST_SCOPE = "from ExpectedDelivery e left join e.vendor v where e.clientId = :clientId "
            + "and (:anyBranch = true or e.branchId in :branchIds) "
            + "and (:anyVendor = true or v.id = :vendorId) "
            + "and (:pattern = '%' "
            + "  or lower(coalesce(e.poNumber, '')) like :pattern escape '!' "
            + "  or lower(coalesce(v.name, '')) like :pattern escape '!' "
            + "  or exists (select 1 from ExpectedDeliveryLine l where l.expectedDelivery = e "
            + "             and lower(l.product.name) like :pattern escape '!')) ";

    String OPEN = "com.procurepal_services.stock_bridge_api.entity.ExpectedDeliveryStatus.OPEN";
    String RECEIVED = "com.procurepal_services.stock_bridge_api.entity.ExpectedDeliveryStatus.RECEIVED";
    String CANCELLED = "com.procurepal_services.stock_bridge_api.entity.ExpectedDeliveryStatus.CANCELLED";
    /** Open, a purchase order, not held for approval, and the supplier doesn't have this revision. */
    String TO_SEND = "(e.status = " + OPEN + " and e.poNumber is not null "
            + "and (e.approval is null or e.approval = 'APPROVED') "
            + "and (e.sentRevision is null or e.sentRevision < e.revision))";
    /** Open and held for approval: waiting for an approver, or back with whoever made it for a change (V78). */
    String TO_APPROVE = "(e.status = " + OPEN + " and e.approval in ('WAITING', 'CHANGE_ASKED'))";
    /** Open, and with the supplier (or only ever written down, which is with them by definition). */
    String WAITING = "(e.status = " + OPEN + " and (e.poNumber is null "
            + "or (e.sentRevision is not null and e.sentRevision >= e.revision)))";
    String LATE = "(e.status = " + OPEN + " and e.expectedDate < :today)";

    /**
     * The Purchase orders list: one page, narrowed by what was typed (PO number, supplier or product),
     * the supplier, the branches the person sees, and the filter chip ({@code view}: ALL, OPEN,
     * TO_SEND, WAITING, LATE, ARRIVED, CALLED_OFF). Unused narrowings are switched off by their flag
     * rather than passed as null, which Postgres cannot type.
     */
    @Query(value = "select e " + LIST_SCOPE
            + "and (:view = 'ALL' "
            + "  or (:view = 'OPEN' and e.status = " + OPEN + ") "
            + "  or (:view = 'TO_SEND' and " + TO_SEND + ") "
            + "  or (:view = 'TO_APPROVE' and " + TO_APPROVE + ") "
            + "  or (:view = 'WAITING' and " + WAITING + ") "
            + "  or (:view = 'LATE' and " + LATE + ") "
            + "  or (:view = 'ARRIVED' and e.status = " + RECEIVED + ") "
            + "  or (:view = 'CALLED_OFF' and e.status = " + CANCELLED + ")) "
            + "order by e.createdAt desc",
            countQuery = "select count(e) " + LIST_SCOPE
            + "and (:view = 'ALL' "
            + "  or (:view = 'OPEN' and e.status = " + OPEN + ") "
            + "  or (:view = 'TO_SEND' and " + TO_SEND + ") "
            + "  or (:view = 'TO_APPROVE' and " + TO_APPROVE + ") "
            + "  or (:view = 'WAITING' and " + WAITING + ") "
            + "  or (:view = 'LATE' and " + LATE + ") "
            + "  or (:view = 'ARRIVED' and e.status = " + RECEIVED + ") "
            + "  or (:view = 'CALLED_OFF' and e.status = " + CANCELLED + "))")
    Page<ExpectedDelivery> search(
            UUID clientId, boolean anyBranch, java.util.Collection<UUID> branchIds, boolean anyVendor, UUID vendorId,
            String pattern, String view, java.time.LocalDate today, Pageable pageable);

    /** How many each filter chip would show, as one row: to send, waiting, late, arrived, all, to approve. */
    @Query("select "
            + "sum(case when " + TO_SEND + " then 1 else 0 end), "
            + "sum(case when " + WAITING + " then 1 else 0 end), "
            + "sum(case when " + LATE + " then 1 else 0 end), "
            + "sum(case when e.status = " + RECEIVED + " then 1 else 0 end), "
            + "count(e), "
            + "sum(case when " + TO_APPROVE + " then 1 else 0 end) " + LIST_SCOPE)
    List<Object[]> counts(
            UUID clientId, boolean anyBranch, java.util.Collection<UUID> branchIds, boolean anyVendor, UUID vendorId,
            String pattern, java.time.LocalDate today);

    /**
     * What was last ordered from this supplier, line by line, newest first: rows of {productId, unit,
     * quantity}. The first row per product is "last time", and its unit is how it is usually bought.
     */
    @Query("select l.product.id, l.unit, l.quantity from ExpectedDeliveryLine l join l.expectedDelivery e "
            + "where e.clientId = :clientId and e.vendor.id = :vendorId and e.status <> " + CANCELLED + " "
            + "and l.product.id in :productIds order by e.createdAt desc")
    List<Object[]> lastOrderedFrom(UUID clientId, UUID vendorId, java.util.Collection<UUID> productIds);

    /**
     * The prices agreed with this supplier on orders not called off, newest first: rows of
     * {productId, unit, price, when the order was made}.
     */
    @Query("select l.product.id, l.unit, l.price, e.createdAt from ExpectedDeliveryLine l join l.expectedDelivery e "
            + "where e.clientId = :clientId and e.vendor.id = :vendorId and e.status <> " + CANCELLED + " "
            + "and l.price is not null and l.product.id in :productIds order by e.createdAt desc")
    List<Object[]> pricesOrderedFrom(UUID clientId, UUID vendorId, java.util.Collection<UUID> productIds, Pageable page);

    /** The same, leaving one order out - the one being changed, whose own lines aren't "coming" to itself. */
    @Query("select l.product.id, l.unit, sum(l.quantity - l.receivedQuantity) from ExpectedDeliveryLine l "
            + "where l.expectedDelivery.clientId = :clientId "
            + "and l.expectedDelivery.status = " + OPEN + " "
            + "and l.expectedDelivery.id <> :excludeId "
            + "and l.quantity > l.receivedQuantity "
            + "and l.product.id in :productIds "
            + "group by l.product.id, l.unit")
    List<Object[]> outstandingByProductAndUnitExcept(UUID clientId, List<UUID> productIds, UUID excludeId);

    /** Products asked of this supplier on orders not called off, most recently ordered first. */
    @Query("select l.product.id, max(e.createdAt) from ExpectedDeliveryLine l join l.expectedDelivery e "
            + "where e.clientId = :clientId and e.vendor.id = :vendorId and e.status <> " + CANCELLED + " "
            + "and l.product.active = true "
            + "group by l.product.id order by max(e.createdAt) desc")
    List<Object[]> productsOrderedFrom(UUID clientId, UUID vendorId, Pageable pageable);

    /** The newest orders that have a supplier, for "who do we order from most recently". */
    List<ExpectedDelivery> findTop200ByClientIdAndVendorIsNotNullOrderByCreatedAtDesc(UUID clientId);

    /** The highest PO sequence this company has used, or null when it has made none. */
    @Query("select max(e.poSequence) from ExpectedDelivery e where e.clientId = :clientId")
    Integer highestPoSequence(UUID clientId);
}
