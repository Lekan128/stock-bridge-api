package com.procurepal_services.stock_bridge_api.superadmin;

import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.superadmin.dto.CatalogResetPreview;
import com.procurepal_services.stock_bridge_api.superadmin.dto.CatalogResetRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Puts one tenant's catalog back to empty without touching the tenant itself - the answer to
 * "they botched their first upload, let them start again" that does not involve deleting and
 * recreating the account.
 *
 * <h2>Why this is not the import undo</h2>
 * {@code POST /api/imports/{id}/undo} already reverses one import, and where it applies it is
 * the better tool - it is self-service, it is scoped to exactly what that file did, and it
 * leaves everything else alone. It is not enough here for two reasons. It works one session at
 * a time, so a client who uploaded four files fixing the last one each time has four undos to
 * do in the right order. And undo DEACTIVATES products rather than deleting them, which is the
 * honest meaning of undo but not of "start again": {@code uq_products_client_id_sku} covers
 * active and inactive rows alike, and {@code ProductCatalogRowHandler}'s SKU lookup does not
 * filter on {@code is_active}, so the next upload of the same file matches those hidden rows
 * and is treated as an UPDATE. Nothing in that path ever sets {@code is_active} back to true.
 * The client re-uploads, is told 500 products were updated, and their catalog still looks
 * empty - the same bug report, one week later.
 *
 * <h2>Why this deletes rows the rest of the codebase refuses to delete</h2>
 * {@code stock_movements} is append-only and every FK into it is RESTRICT; design 6.6 says undo
 * never deletes ledger rows. That rule protects a ledger that means something. A tenant whose
 * entire history is one bad spreadsheet has no such ledger, and the platform operator deleting
 * it deliberately, having typed the tenant's slug to say so, is a different act from a tenant
 * clicking undo. What is NOT different is the part that protects other people: if anyone has
 * ordered these products, this refuses. See {@link #blockersFor}.
 *
 * <h2>Tenant scoping</h2>
 * No {@code TenantScopeExecutor} here. A super admin belongs to no tenant, so
 * {@code TenantResolutionFilter} leaves the Hibernate tenant filter disabled for these
 * requests, and every statement below carries its own explicit {@code client_id} predicate -
 * the same way the rest of {@link SuperAdminClientService} reaches tenant tables.
 */
@Service
@RequiredArgsConstructor
public class ClientCatalogResetService {

    private static final Logger log = LoggerFactory.getLogger(ClientCatalogResetService.class);

    /** How many blocking products to name before the message just gives a count. */
    private static final int MAX_BLOCKERS_LISTED = 10;

    /**
     * Past this much stock-moving history, a tenant is treated as a going concern rather than an
     * onboarding that went wrong, and the reset stops being something an ops user can do by
     * accident.
     *
     * <p>Thirty days because that is comfortably longer than any onboarding that is still going
     * badly - a client who cannot get their catalog in after a month has a support problem, not
     * an upload problem - and comfortably shorter than the tenure of anyone whose ledger is
     * worth protecting. It is deliberately not tuned finer than that: the number decides which
     * of two sentences an ops user reads, not whether data survives.
     */
    private static final long ESTABLISHED_AFTER_DAYS = 30;

    @PersistenceContext
    private EntityManager entityManager;

    private final ClientRepository clientRepository;

    /** Dry run. Identical numbers to what {@link #reset} would remove, computed the same way. */
    @Transactional(readOnly = true)
    public CatalogResetPreview preview(UUID clientId, boolean includeVendorDirectory, boolean resetSkuCounters) {
        Client client = findOrThrow(clientId);
        return describe(client, includeVendorDirectory, resetSkuCounters);
    }

    /**
     * Deletes, in foreign-key order, and returns what went.
     *
     * <p>The preview is recomputed inside this transaction rather than trusted from the request:
     * an order placed between the dialog opening and the button being pressed has to block the
     * reset, and the only read that can promise that is one taken here.
     */
    @Transactional
    public CatalogResetPreview reset(UUID clientId, CatalogResetRequest request) {
        Client client = findOrThrow(clientId);
        if (!request.confirms(client.getSlug())) {
            throw new CatalogResetExceptions.NotConfirmed(
                    CatalogResetRequest.requiredPhraseFor(client.getSlug()));
        }

        CatalogResetPreview planned =
                describe(client, request.includeVendorDirectory(), request.resetSkuCounters());
        if (planned.blocked()) {
            throw new CatalogResetExceptions.Blocked(planned);
        }
        if (planned.activity().established() && !request.acknowledgeEstablished()) {
            throw new CatalogResetExceptions.Blocked(new CatalogResetPreview(
                    client.getId(),
                    client.getName(),
                    client.getSlug(),
                    true,
                    CatalogResetPreview.ESTABLISHED_CUSTOMER,
                    establishedMessage(client, planned),
                    List.of(),
                    planned.counts(),
                    planned.activity()));
        }

        // Order is the whole correctness argument, and every step below is forced by a
        // constraint rather than chosen:
        //
        //   1. stock_movement_allocations -> stock_movements is RESTRICT on BOTH ids (V19), so
        //      allocations go before any movement can.
        //   2. stock_movements -> products is RESTRICT (V1). Movements go before products.
        //   3. products takes product_vendors with it, and packs/price_tiers under those, all
        //      CASCADE (V19/V24). cart_items CASCADE too - including OTHER tenants' cart lines,
        //      which is why the preview counts them. order_items.buyer_product_id and
        //      products.source_product_id are SET NULL, so past orders and derived listings
        //      survive with a cleared link.
        //   4. import_sessions last: products.import_batch_id and stock_movements.import_batch_id
        //      are both RESTRICT against it (V20), so the sessions cannot go until the rows that
        //      point at them already have. import_session_rows CASCADE from the session.
        //
        // Anything reordered here does not corrupt data, it throws - which is the property the
        // RESTRICTs were put there to have.
        long allocations = execute("""
                DELETE FROM stock_movement_allocations a
                USING stock_movements m
                WHERE (a.in_movement_id = m.id OR a.out_movement_id = m.id)
                  AND m.client_id = :clientId
                """, clientId);
        long movements = execute("DELETE FROM stock_movements WHERE client_id = :clientId", clientId);
        long products = execute("DELETE FROM products WHERE client_id = :clientId", clientId);
        long sessions = execute("DELETE FROM import_sessions WHERE client_id = :clientId", clientId);

        long vendors = 0;
        if (request.includeVendorDirectory()) {
            vendors = execute("DELETE FROM company_vendors WHERE client_id = :clientId", clientId);
        }
        long skuCounters = 0;
        if (request.resetSkuCounters()) {
            skuCounters = execute("DELETE FROM product_sku_settings WHERE client_id = :clientId", clientId);
        }

        // The one durable record that this happened. There is no row left to carry it: the
        // audit trail this operation destroys is the audit trail that would otherwise have
        // described it.
        log.warn("Catalog reset for client {} ({}): {} products, {} movements, {} allocations, "
                        + "{} import sessions, {} vendors, {} sku counters",
                client.getSlug(), clientId, products, movements, allocations, sessions, vendors, skuCounters);

        CatalogResetPreview.Counts planCounts = planned.counts();
        return new CatalogResetPreview(
                client.getId(),
                client.getName(),
                client.getSlug(),
                false,
                null,
                "%s and everything behind it has been cleared. %s can upload again from scratch."
                        .formatted(productsPhrase(products), client.getName()),
                List.of(),
                new CatalogResetPreview.Counts(
                        products,
                        planCounts.activeProducts(),
                        movements,
                        allocations,
                        planCounts.productVendors(),
                        sessions,
                        vendors,
                        skuCounters,
                        planCounts.foreignCartLines(),
                        planCounts.orderLinksCleared(),
                        planCounts.derivedProductLinksCleared()),
                planned.activity());
    }

    private CatalogResetPreview describe(
            Client client, boolean includeVendorDirectory, boolean resetSkuCounters) {
        UUID clientId = client.getId();
        List<CatalogResetPreview.Blocker> blockers = blockersFor(clientId);

        CatalogResetPreview.Counts counts = new CatalogResetPreview.Counts(
                count("SELECT count(*) FROM products WHERE client_id = :clientId", clientId),
                count("SELECT count(*) FROM products WHERE client_id = :clientId AND is_active", clientId),
                count("SELECT count(*) FROM stock_movements WHERE client_id = :clientId", clientId),
                count("""
                        SELECT count(*) FROM stock_movement_allocations a
                        WHERE a.in_movement_id IN (SELECT id FROM stock_movements WHERE client_id = :clientId)
                           OR a.out_movement_id IN (SELECT id FROM stock_movements WHERE client_id = :clientId)
                        """, clientId),
                count("SELECT count(*) FROM product_vendors WHERE client_id = :clientId", clientId),
                count("SELECT count(*) FROM import_sessions WHERE client_id = :clientId", clientId),
                includeVendorDirectory
                        ? count("SELECT count(*) FROM company_vendors WHERE client_id = :clientId", clientId)
                        : 0,
                resetSkuCounters
                        ? count("SELECT count(*) FROM product_sku_settings WHERE client_id = :clientId", clientId)
                        : 0,
                count("""
                        SELECT count(*) FROM cart_items ci
                        JOIN products p ON p.id = ci.product_id
                        JOIN carts c ON c.id = ci.cart_id
                        WHERE p.client_id = :clientId AND c.client_id <> :clientId
                        """, clientId),
                count("""
                        SELECT count(*) FROM order_items oi
                        JOIN products p ON p.id = oi.buyer_product_id
                        WHERE p.client_id = :clientId
                        """, clientId),
                count("""
                        SELECT count(*) FROM products derived
                        JOIN products source ON source.id = derived.source_product_id
                        WHERE source.client_id = :clientId AND derived.client_id <> :clientId
                        """, clientId));

        CatalogResetPreview.Activity activity = activityOf(clientId);

        if (!blockers.isEmpty()) {
            return new CatalogResetPreview(
                    clientId,
                    client.getName(),
                    client.getSlug(),
                    true,
                    CatalogResetPreview.ORDERED_PRODUCTS,
                    blockedMessage(blockers),
                    blockers,
                    counts,
                    activity);
        }
        return new CatalogResetPreview(
                clientId,
                client.getName(),
                client.getSlug(),
                false,
                null,
                plannedMessage(counts),
                List.of(),
                counts,
                activity);
    }

    /**
     * How long this tenant has actually been running, which is the difference between the case
     * this feature was built for and the one it must not be used on.
     *
     * <p>Deliberately NOT a blocker on its own - {@link #describe} reports it and {@link #reset}
     * refuses only an UNACKNOWLEDGED one. A tenant who has genuinely been trading for a year and
     * genuinely needs a reset has to stay possible, or the next person just does it by hand in
     * the database, which is the outcome this whole endpoint exists to prevent.
     */
    private CatalogResetPreview.Activity activityOf(UUID clientId) {
        // JPQL, not a native query like everything else here: an untyped native aggregate comes
        // back as whatever the driver felt like (a java.sql.Timestamp), and casting that to
        // OffsetDateTime throws for every tenant that actually has movements - i.e. exactly the
        // ones this check exists to catch. JPQL knows the mapped type.
        OffsetDateTime firstMovementAt = entityManager
                .createQuery(
                        "SELECT MIN(m.createdAt) FROM StockMovement m WHERE m.clientId = :clientId",
                        OffsetDateTime.class)
                .setParameter("clientId", clientId)
                .getSingleResult();

        // DELIVERED/RECEIVED only: a cancelled order proves nothing, and one still in flight is
        // not yet history worth protecting. Either of these means goods actually changed hands.
        long receivedOrders = count("""
                SELECT count(*) FROM orders
                WHERE client_id = :clientId AND status IN ('DELIVERED', 'RECEIVED')
                """, clientId);

        long daysActive =
                firstMovementAt == null ? 0 : Duration.between(firstMovementAt, OffsetDateTime.now()).toDays();
        boolean established = receivedOrders > 0 || daysActive >= ESTABLISHED_AFTER_DAYS;

        return new CatalogResetPreview.Activity(firstMovementAt, daysActive, receivedOrders, established);
    }

    /** The are-you-sure, stated in whichever terms actually apply to this tenant. */
    private String establishedMessage(Client client, CatalogResetPreview planned) {
        CatalogResetPreview.Activity activity = planned.activity();
        String history = activity.receivedOrders() > 0
                ? "%s has taken delivery of %s marketplace %s"
                        .formatted(
                                client.getName(),
                                activity.receivedOrders(),
                                activity.receivedOrders() == 1 ? "order" : "orders")
                : "%s has been moving stock for %s days".formatted(client.getName(), activity.daysActive());
        return history
                + ", so this does not look like an onboarding that went wrong. Clearing the catalog would "
                + "delete %s and %s stock movements permanently. If that is really what you want, confirm "
                        .formatted(productsPhrase(planned.counts().products()), planned.counts().stockMovements())
                + "that you have checked with this customer first.";
    }

    /**
     * The one thing that refuses a reset: a product of this tenant's that someone has ordered.
     *
     * <p>{@code order_items.product_id} is ON DELETE RESTRICT (V6) precisely so an order line
     * always resolves to a real catalog row, and the database would refuse this delete anyway -
     * but as a constraint violation halfway through the sequence, which tells an ops user
     * nothing. Asking first turns the same refusal into a sentence naming the products.
     *
     * <p>Note what is NOT a blocker. Stock movements and allocations of the tenant's own are
     * deleted: clearing inventory to zero is the entire request, and those rows describe only
     * this tenant's own bookkeeping. Cart lines are not a commitment and are counted, not
     * refused. Orders this tenant placed as a BUYER are untouched - those lines point at the
     * seller's products, and only the {@code buyer_product_id} link back into this inventory is
     * cleared.
     */
    private List<CatalogResetPreview.Blocker> blockersFor(UUID clientId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT p.id, p.name, p.sku, count(*) AS order_lines
                        FROM order_items oi
                        JOIN products p ON p.id = oi.product_id
                        WHERE p.client_id = :clientId
                        GROUP BY p.id, p.name, p.sku
                        ORDER BY count(*) DESC, p.name ASC
                        LIMIT :limit
                        """)
                .setParameter("clientId", clientId)
                .setParameter("limit", MAX_BLOCKERS_LISTED)
                .getResultList();

        List<CatalogResetPreview.Blocker> blockers = new ArrayList<>();
        for (Object[] row : rows) {
            blockers.add(new CatalogResetPreview.Blocker(
                    (UUID) row[0], (String) row[1], (String) row[2], ((Number) row[3]).longValue()));
        }
        return blockers;
    }

    private String blockedMessage(List<CatalogResetPreview.Blocker> blockers) {
        String listed = blockers.size() == MAX_BLOCKERS_LISTED ? "At least " + MAX_BLOCKERS_LISTED : "";
        return (listed.isEmpty()
                        ? "%s of this tenant's %s".formatted(blockers.size(), noun(blockers.size()))
                        : "%s of this tenant's products".formatted(listed))
                + " have already been ordered, so the catalog can't be cleared - deleting them "
                + "would leave those orders pointing at nothing. Cancel or fulfil the orders "
                + "first, or deactivate these products one at a time instead.";
    }

    private String plannedMessage(CatalogResetPreview.Counts counts) {
        if (counts.products() == 0 && counts.importSessions() == 0) {
            return "This tenant has no products or upload history to clear.";
        }
        return "%s, %s stock %s and %s upload %s will be deleted permanently."
                .formatted(
                        productsPhrase(counts.products()),
                        counts.stockMovements(),
                        counts.stockMovements() == 1 ? "movement" : "movements",
                        counts.importSessions(),
                        counts.importSessions() == 1 ? "record" : "records");
    }

    private static String productsPhrase(long products) {
        return products + " " + noun(products);
    }

    private static String noun(long count) {
        return count == 1 ? "product" : "products";
    }

    private long count(String sql, UUID clientId) {
        Number result = (Number) entityManager.createNativeQuery(sql)
                .setParameter("clientId", clientId)
                .getSingleResult();
        return result.longValue();
    }

    private long execute(String sql, UUID clientId) {
        return entityManager.createNativeQuery(sql).setParameter("clientId", clientId).executeUpdate();
    }

    private Client findOrThrow(UUID clientId) {
        return clientRepository.findById(clientId).orElseThrow(ClientNotFoundException::new);
    }
}
