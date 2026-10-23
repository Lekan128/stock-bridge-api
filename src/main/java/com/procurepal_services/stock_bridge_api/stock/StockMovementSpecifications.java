package com.procurepal_services.stock_bridge_api.stock;

import com.procurepal_services.stock_bridge_api.entity.MovementType;
import com.procurepal_services.stock_bridge_api.entity.OutReason;
import com.procurepal_services.stock_bridge_api.entity.StockMovement;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/** Builds the tenant-scoping predicate explicitly - see ProductSpecifications for the same rationale. */
final class StockMovementSpecifications {

    private StockMovementSpecifications() {
    }

    /**
     * The ledger, filtered. Backs both {@code GET /api/products/{id}/stock/history} (one
     * product, no date range) and the tenant-wide stock in/out report at {@code GET
     * /api/stock/movements}.
     *
     * <h2>from/to bracket occurredAt, NOT createdAt</h2>
     * A report's date range is a range of real-world dates - when a delivery arrived, when a
     * sale happened - not of data-entry timestamps. The two were the same thing until bulk
     * stock-in made backdating ordinary (BULK_IMPORT_DESIGN.md section 8.4, {@code
     * StockMovement.occurredAt}), and under a {@code createdAt} filter last month's deliveries
     * keyed in today land in this month's report, where they answer a question nobody asked.
     * {@code AnalyticsService} brackets the same column for the same reason, which is what lets
     * the dashboard's Stock In/Out Value card and the report that drills into it agree on a
     * total. Backed by {@code idx_stock_movements_client_id_occurred_at} (V28).
     *
     * <p>{@code createdAt} has not gone anywhere - it is still published on every row and is
     * still what {@code history} sorts by. "When did this arrive" and "when did somebody type
     * this in" are both real questions; only the first one is a date range.
     *
     * <h2>The fetch joins are what make the report one query instead of 2N+1</h2>
     * Every row of the report names its product and its supplier, and both associations are
     * LAZY, so without these the endpoint issues two extra queries per row - on the one screen
     * whose whole job is to list movements. LEFT for {@code companyVendor} because OUT and
     * ADJUSTMENT rows legitimately have none and an inner join would silently drop every
     * stock-out from a report whose name promises stock-outs; INNER for {@code product}, which
     * is NOT NULL on the table.
     *
     * <p>The product join is built once and reused for the {@code productId} predicate rather
     * than letting {@code root.get("product")} open a second one, so filtering by product does
     * not join the same table twice.
     *
     * <p>The result-type guard is not optional: Spring Data reuses a specification for its own
     * {@code COUNT} query when paging, and a fetch join in a count query is invalid JPQL. Same
     * guard, same reason, as {@link #lotsForProduct}.
     */
    static Specification<StockMovement> forTenant(
            UUID clientId,
            UUID productId,
            UUID companyVendorId,
            OffsetDateTime from,
            OffsetDateTime to,
            MovementType movementType) {
        return forTenant(clientId, productId, companyVendorId, from, to, movementType, null, false);
    }

    /**
     * The same, narrowed by why a stock-out happened (V46): {@code outReason} keeps the stock-outs
     * with that reason; {@code noReason} keeps the stock-outs where none was given. Mirrored exactly
     * by {@code StockMovementRepository.summarise}, so the report's footer never disagrees with it.
     */
    static Specification<StockMovement> forTenant(
            UUID clientId,
            UUID productId,
            UUID companyVendorId,
            OffsetDateTime from,
            OffsetDateTime to,
            MovementType movementType,
            OutReason outReason,
            boolean noReason) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("clientId"), clientId));

            Path<?> product = root.get("product");
            if (query != null && query.getResultType() != Long.class && query.getResultType() != long.class) {
                product = (Path<?>) root.fetch("product", JoinType.INNER);
                root.fetch("companyVendor", JoinType.LEFT);
            }

            if (productId != null) {
                predicates.add(cb.equal(product.get("id"), productId));
            }
            if (companyVendorId != null) {
                predicates.add(cb.equal(root.get("companyVendor").get("id"), companyVendorId));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("occurredAt"), to));
            }
            if (movementType != null) {
                predicates.add(cb.equal(root.get("movementType"), movementType));
            }
            if (outReason != null) {
                predicates.add(cb.equal(root.get("outReason"), outReason));
            }
            if (noReason) {
                predicates.add(cb.equal(root.get("movementType"), MovementType.OUT));
                predicates.add(cb.isNull(root.get("outReason")));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * Every {@code IN} movement (lot) of one product, with its supplier already joined - the row
     * set behind {@code GET /api/products/{id}/lots} (UNIT_UX_CONTRACT.md section 4). Sorting is
     * the caller's, since the lot list and a future lot report want different orders while the
     * predicate is the same.
     *
     * <h2>The LEFT JOIN FETCH is the point of having a second specification</h2>
     * Every row of the response names its supplier, and {@code StockMovement.companyVendor} is
     * LAZY - so without this the endpoint issues one extra query per lot, on the one screen
     * whose whole job is to list every lot a product has. LEFT rather than inner because a lot
     * recorded before any supplier was on file legitimately has none, and an inner join would
     * quietly drop exactly the oldest stock, which FIFO draws from first.
     *
     * <p>The result-type guard is not optional: Spring Data reuses a specification for its own
     * {@code COUNT} query when one is needed, and a fetch join in a count query is invalid JPQL.
     * {@link #forTenant} above needs no such guard because it fetches nothing.
     */
    static Specification<StockMovement> lotsForProduct(UUID clientId, UUID productId) {
        return lotsForProduct(clientId, productId, null);
    }

    /**
     * One branch's lots (V51, V53): what is on that shelf to draw from - deliveries, and stock that
     * arrived there on a waybill (a TRANSFER_IN is a lot of its own). {@code branchId} null: every branch.
     */
    static Specification<StockMovement> lotsForProduct(UUID clientId, UUID productId, UUID branchId) {
        return (root, query, cb) -> {
            if (query != null && query.getResultType() != Long.class && query.getResultType() != long.class) {
                root.fetch("companyVendor", JoinType.LEFT);
            }
            jakarta.persistence.criteria.Predicate lots = cb.and(
                    cb.equal(root.get("clientId"), clientId),
                    cb.equal(root.get("product").get("id"), productId),
                    root.get("movementType").in(MovementType.IN, MovementType.TRANSFER_IN));
            return branchId == null ? lots : cb.and(lots, cb.equal(root.get("branchId"), branchId));
        };
    }

    /**
     * Only movements at these branches (V51) - the branch filter, and the D-U3 rule that people see
     * only the branches they may: a caller passes the branches the person sees, narrowed to one when
     * they ask for one.
     */
    public static Specification<StockMovement> atBranches(java.util.Collection<UUID> branchIds) {
        return (root, query, cb) -> root.get("branchId").in(branchIds);
    }
}
