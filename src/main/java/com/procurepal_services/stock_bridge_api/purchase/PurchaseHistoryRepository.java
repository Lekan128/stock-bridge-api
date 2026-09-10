package com.procurepal_services.stock_bridge_api.purchase;

import com.procurepal_services.stock_bridge_api.purchase.dto.PurchaseSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * The one place "everything this company bought" is actually a single ORDER BY across two
 * unrelated tables - {@code orders} (a marketplace purchase) and {@code stock_movements} (a
 * manually-recorded delivery, on or off platform).
 *
 * <h2>Why native SQL and not two Spring Data queries merged in Java</h2>
 * Merging two independently-paginated, independently-sorted queries in application code cannot
 * produce a correct page N: neither query knows how many rows of the OTHER kind sort before it,
 * so both the page boundary and the total count would be wrong for anyone with a real mix of
 * orders and manual stock-ins. A single {@code UNION ALL} let the database do the one sort and
 * the one {@code LIMIT}/{@code OFFSET} that answers "page N of the true merged order" correctly.
 * This repository resolves only the merged (id, source, occurred_at) pointer, cheaply; the actual
 * order/movement rows are batch-hydrated by {@link PurchaseHistoryService}.
 *
 * <h2>Why the WHERE clause is built in Java rather than one fixed {@code @Query}</h2>
 * Every filter here - the vendor, the source, the date range - is optional, and a native query
 * that always binds a value for an absent filter runs into Postgres being unable to infer a bind
 * parameter's type from an {@code IS NULL} check alone ("could not determine data type of
 * parameter"). Appending a predicate only when its filter is actually present sidesteps that
 * entirely: an omitted filter never binds a null parameter at all, rather than binding one and
 * hoping Postgres infers its type. Every fragment appended below is a fixed string with no
 * request data in it - the values themselves are always bound as parameters - so this stays free
 * of SQL injection despite being assembled at runtime.
 *
 * <h2>Tenant isolation is explicit here, not inherited</h2>
 * {@code TenantAwareEntity}'s Hibernate {@code @Filter} only applies to HQL/Criteria queries
 * against mapped entities - a native query bypasses it entirely. {@code client_id = :buyerClientId}
 * is therefore required, not defensive, on both branches of the union, matching the
 * belt-and-braces rule {@code TenantScopedRepository}'s own javadoc describes.
 */
@Repository
class PurchaseHistoryRepository {

    @PersistenceContext
    private EntityManager entityManager;

    List<PurchasePointer> findPointers(PurchaseHistoryFilter filter, int limit, long offset) {
        Built built = build(filter);
        if (built == null) {
            return List.of();
        }
        Query query = entityManager.createNativeQuery(
                built.sql() + " ORDER BY occurred_at DESC, id DESC LIMIT :limit OFFSET :offset");
        bind(query, built.params());
        query.setParameter("limit", limit);
        query.setParameter("offset", offset);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<PurchasePointer> pointers = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            pointers.add(new PurchasePointer(
                    (UUID) row[0],
                    PurchaseSource.valueOf((String) row[1]),
                    toOffsetDateTime(row[2]),
                    (UUID) row[3]));
        }
        return pointers;
    }

    long count(PurchaseHistoryFilter filter) {
        Built built = build(filter);
        if (built == null) {
            return 0;
        }
        Query query = entityManager.createNativeQuery("SELECT COUNT(*) FROM (" + built.sql() + ") merged");
        bind(query, built.params());
        return ((Number) query.getSingleResult()).longValue();
    }

    private static OffsetDateTime toOffsetDateTime(Object value) {
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant().atOffset(java.time.ZoneOffset.UTC);
        }
        return null;
    }

    private record Built(String sql, List<Object[]> params) {}

    private Built build(PurchaseHistoryFilter filter) {
        List<Object[]> params = new ArrayList<>();
        params.add(new Object[] {"buyerClientId", filter.buyerClientId()});

        StringBuilder orderPredicate = new StringBuilder();
        StringBuilder movementPredicate = new StringBuilder();
        if (filter.companyVendorId() != null) {
            params.add(new Object[] {"companyVendorId", filter.companyVendorId()});
            orderPredicate.append(" AND cv.id = :companyVendorId");
            movementPredicate.append(" AND m.company_vendor_id = :companyVendorId");
        }
        if (filter.from() != null) {
            params.add(new Object[] {"fromDate", filter.from()});
            orderPredicate.append(" AND o.placed_at >= :fromDate");
            movementPredicate.append(" AND m.occurred_at >= :fromDate");
        }
        if (filter.to() != null) {
            params.add(new Object[] {"toDate", filter.to()});
            orderPredicate.append(" AND o.placed_at <= :toDate");
            movementPredicate.append(" AND m.occurred_at <= :toDate");
        }

        boolean includeOrders = filter.source() == null || filter.source() == PurchaseSource.MARKETPLACE_ORDER;
        boolean includeStockIns = filter.source() == null || filter.source() == PurchaseSource.MANUAL_STOCK_IN;

        List<String> branches = new ArrayList<>();
        if (includeOrders) {
            branches.add("SELECT o.id AS id, 'MARKETPLACE_ORDER' AS source, o.placed_at AS occurred_at, "
                    + "cv.id AS company_vendor_id "
                    + "FROM orders o "
                    + "JOIN company_vendors cv ON cv.platform_client_id = o.seller_client_id AND cv.client_id = o.client_id "
                    + "WHERE o.client_id = :buyerClientId AND o.placed_at IS NOT NULL"
                    + orderPredicate);
        }
        if (includeStockIns) {
            branches.add("SELECT m.id AS id, 'MANUAL_STOCK_IN' AS source, m.occurred_at AS occurred_at, "
                    + "m.company_vendor_id AS company_vendor_id "
                    + "FROM stock_movements m "
                    + "WHERE m.client_id = :buyerClientId AND m.movement_type = 'IN' AND m.company_vendor_id IS NOT NULL"
                    + movementPredicate);
        }
        if (branches.isEmpty()) {
            return null;
        }
        return new Built(String.join(" UNION ALL ", branches), params);
    }

    private static void bind(Query query, List<Object[]> params) {
        for (Object[] param : params) {
            query.setParameter((String) param[0], param[1]);
        }
    }
}
