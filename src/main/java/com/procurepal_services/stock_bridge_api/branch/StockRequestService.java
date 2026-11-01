package com.procurepal_services.stock_bridge_api.branch;

import com.procurepal_services.stock_bridge_api.branch.dto.NoneInStoreLine;
import com.procurepal_services.stock_bridge_api.branch.dto.StockRequestCreateRequest;
import com.procurepal_services.stock_bridge_api.branch.dto.StockRequestResponse;
import com.procurepal_services.stock_bridge_api.entity.NotificationType;
import com.procurepal_services.stock_bridge_api.founding.WhatsAppNumbers;
import com.procurepal_services.stock_bridge_api.notification.NotificationService;
import com.procurepal_services.stock_bridge_api.push.PushMessage;
import com.procurepal_services.stock_bridge_api.push.PushService;
import com.procurepal_services.stock_bridge_api.settlement.PayoutCadence;
import com.procurepal_services.stock_bridge_api.stock.StockManagementService;
import com.procurepal_services.stock_bridge_api.stock.StockWriteNotUndoableException;
import com.procurepal_services.stock_bridge_api.stock.StockWritePermissionGuard;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One area asking another of its branch for stock (STOCK_AREAS_PART_5.md, STOCK_AREAS_PLAN.md §5.4, D-A7): the
 * Kitchen asks the Store, whose people move it - a move naming the request fills it - or say there's none in the
 * store, which the people who buy then see. A request changes no figure: only the moves that fill it do.
 *
 * <p>Who may (D-E9, checked from the database on every call): ask - someone who works in the asking area and can
 * record stock in or out; fill or say "none" - someone who works in the area asked and can record a stock-out;
 * cancel - the asker, or someone in the asking area, while nothing on it has been moved.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class StockRequestService {

    /** How long a "none in the store" line stays on the buyer's Low stock page. */
    static final Duration NONE_IN_STORE_SHOWN = Duration.ofDays(14);

    /** "7:42 AM" in a notification: the time of day where the business is. */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
            .withZone(PayoutCadence.ZONE);

    private final NamedParameterJdbcTemplate jdbc;
    private final BranchStockService branchStockService;
    private final BranchAccessService branchAccessService;
    private final AreaAccessService areaAccessService;
    private final StockManagementService stockManagementService;
    private final StockWritePermissionGuard stockWritePermissionGuard;
    private final NotificationService notificationService;
    private final AreaPeople areaPeople;
    private final PushService pushService;

    /** One area as a request needs it. */
    private record AreaRow(UUID id, String name, boolean keepsStock, boolean active) {
    }

    public StockRequestResponse ask(StockRequestCreateRequest request, UUID userId) {
        UUID clientId = requireTenantId();
        // Sent again after a lost reply, or late from a phone that was offline: the first one stands.
        if (exists(request.id(), clientId)) {
            return get(request.id(), userId);
        }
        UUID branchId = branchStockService.resolveForWrite(request.branchId(), userId);
        branchAccessService.requireMayRecordAt(userId, branchId);
        AreaRow to = requireArea(request.toAreaId(), branchId);
        AreaRow from = requireArea(request.fromAreaId(), branchId);
        if (!to.active()) {
            throw new AreaClosedException(to.name());
        }
        if (!from.active()) {
            throw new AreaClosedException(from.name());
        }
        if (!from.keepsStock()) {
            throw new AreaKeepsNoStockException(from.name());
        }
        if (from.id().equals(to.id())) {
            throw new BranchChangeRefusedException("Choose two different areas: this would ask an area for its own stock.");
        }
        areaAccessService.requireMayRecordIn(userId, branchId, to.id());
        Set<UUID> seen = new HashSet<>();
        for (StockRequestCreateRequest.Line line : request.lines()) {
            if (!seen.add(line.productId())) {
                throw new BranchChangeRefusedException("Each product goes on a request once.");
            }
        }

        OffsetDateTime askedAt = request.occurredAt() != null ? request.occurredAt() : OffsetDateTime.now();
        jdbc.update("""
                INSERT INTO stock_requests (id, client_id, branch_id, from_area_id, to_area_id, asked_by, asked_at, note)
                VALUES (:id, :clientId, :branchId, :fromAreaId, :toAreaId, :userId, :askedAt, :note)
                """, new MapSqlParameterSource()
                        .addValue("id", request.id())
                        .addValue("clientId", clientId)
                        .addValue("branchId", branchId)
                        .addValue("fromAreaId", from.id())
                        .addValue("toAreaId", to.id())
                        .addValue("userId", userId)
                        .addValue("askedAt", askedAt)
                        .addValue("note", blankToNull(request.note())));
        for (StockRequestCreateRequest.Line line : request.lines()) {
            StockManagementService.AreaMovePiece piece =
                    stockManagementService.inStockUnits(line.productId(), line.quantity(), blankToNull(line.unit()));
            jdbc.update("""
                    INSERT INTO stock_request_lines (request_id, product_id, quantity, entered_unit, entered_quantity)
                    VALUES (:id, :productId, :quantity, :enteredUnit, :enteredQuantity)
                    """, new MapSqlParameterSource()
                            .addValue("id", request.id())
                            .addValue("productId", line.productId())
                            .addValue("quantity", piece.quantity())
                            .addValue("enteredUnit", piece.enteredUnit())
                            .addValue("enteredQuantity", piece.enteredQuantity()));
        }
        log.info("Stock request {}: {} lines, area {} asks {} at branch {}", request.id(), request.lines().size(), to.id(),
                from.id(), branchId);

        int items = request.lines().size();
        String asker = areaPeople.name(userId);
        for (UUID person : areaPeople.of(from.id(), branchId, userId)) {
            notificationService.notifyClient(clientId, person, NotificationType.STOCK_REQUEST,
                    "The " + to.name() + " asked for " + itemsWord(items),
                    (asker != null ? asker + " at " : "At ") + TIME.format(askedAt), link(request.id()), null);
        }
        // On the phones of those who can move it, even with the app closed (owner, 2026-11-01).
        List<String> products = jdbc.queryForList("""
                SELECT p.name FROM stock_request_lines rl JOIN products p ON p.id = rl.product_id
                WHERE rl.request_id = :id ORDER BY p.name
                """, new MapSqlParameterSource("id", request.id()), String.class);
        pushService.notify(moverIds(from.id(), branchId, userId), new PushMessage(
                "The " + to.name() + " asked for " + itemsWord(items),
                String.join(", ", products) + " · " + (asker != null ? asker + " at " : "at ") + TIME.format(askedAt),
                tag(request.id()), link(request.id()), false));
        return get(request.id(), userId);
    }

    /**
     * A move naming {@code requestId} was made, undone or taken back (Part 4): each line's moved amount is counted
     * again from the moves that stand, and the request is done once every line is moved or none in the store.
     * Called by {@link AreaMoveService} in the move's own transaction.
     *
     * @param filledBy who made the move that was just recorded, to tell the asker what moved; null when it was undone.
     */
    void recount(UUID requestId, UUID filledBy) {
        Map<String, Object> request = requireRequest(requestId, requireTenantId());
        jdbc.update("""
                UPDATE stock_request_lines rl SET quantity_moved = COALESCE((
                    SELECT SUM(ml.quantity) FROM area_moves m JOIN area_move_lines ml ON ml.move_id = m.id
                    WHERE m.request_id = rl.request_id AND ml.product_id = rl.product_id
                      AND m.status NOT IN ('VOIDED', 'CANCELLED')), 0)
                WHERE rl.request_id = :id
                """, new MapSqlParameterSource("id", requestId));
        boolean wasOpen = "OPEN".equals(request.get("status"));
        String now = settle(requestId);
        if (filledBy != null && wasOpen) {
            tellAskerMoved(request, filledBy);
        }
        if (wasOpen && "DONE".equals(now)) {
            String mover = areaPeople.name(filledBy);
            pushGone(request, "Moved ✓", mover != null ? "Moved by " + mover : "It's been moved");
        }
    }

    /** Whether a move from {@code fromAreaId} to {@code toAreaId} may fill {@code requestId}: it's open and matches. */
    boolean fillable(UUID requestId, UUID branchId, UUID fromAreaId, UUID toAreaId) {
        List<Map<String, Object>> found = jdbc.queryForList("""
                SELECT 1 FROM stock_requests WHERE id = :id AND client_id = :clientId AND status = 'OPEN'
                  AND branch_id = :branchId AND from_area_id = :fromAreaId AND to_area_id = :toAreaId
                """, new MapSqlParameterSource()
                        .addValue("id", requestId)
                        .addValue("clientId", requireTenantId())
                        .addValue("branchId", branchId)
                        .addValue("fromAreaId", fromAreaId)
                        .addValue("toAreaId", toAreaId));
        return !found.isEmpty();
    }

    /** "None in the store" for these lines: someone who works in the area asked. The asker is told. */
    public StockRequestResponse noneInStore(UUID requestId, List<UUID> productIds, UUID userId) {
        UUID clientId = requireTenantId();
        Map<String, Object> request = requireRequest(requestId, clientId);
        if (!"OPEN".equals(request.get("status"))) {
            throw new BranchChangeRefusedException("CANCELLED".equals(request.get("status"))
                    ? "This request was cancelled, so there's nothing to answer."
                    : "This request has been answered already.");
        }
        UUID branchId = (UUID) request.get("branch_id");
        stockWritePermissionGuard.require(userId, "STOCK_OUT");
        branchAccessService.requireMayRecordAt(userId, branchId);
        areaAccessService.requireMayRecordIn(userId, branchId, (UUID) request.get("from_area_id"));
        int marked = jdbc.update("""
                UPDATE stock_request_lines SET none_in_store = TRUE
                WHERE request_id = :id AND product_id = ANY(:products) AND NOT none_in_store AND quantity_moved < quantity
                """, new MapSqlParameterSource()
                        .addValue("id", requestId)
                        .addValue("products", productIds.toArray(UUID[]::new)));
        if ("DONE".equals(settle(requestId))) {
            pushGone(request, "Answered ✓", "Answered by " + areaPeople.name(userId));
        }
        if (marked > 0) {
            List<String> names = jdbc.queryForList("""
                    SELECT p.name FROM stock_request_lines rl JOIN products p ON p.id = rl.product_id
                    WHERE rl.request_id = :id AND rl.product_id = ANY(:products) ORDER BY p.name
                    """, new MapSqlParameterSource()
                            .addValue("id", requestId)
                            .addValue("products", productIds.toArray(UUID[]::new)), String.class);
            tellAsker(request, userId, "The " + areaName((UUID) request.get("from_area_id")) + " has none of: "
                    + String.join(", ", names), "You asked at " + TIME.format(timestamp(request.get("asked_at"))));
        }
        log.info("Stock request {}: {} lines none in store, by {}", requestId, marked, userId);
        return get(requestId, userId);
    }

    /** Takes back a request nothing has been moved for: the asker, or someone who works in the asking area. */
    public StockRequestResponse cancel(UUID requestId, UUID userId) {
        UUID clientId = requireTenantId();
        Map<String, Object> request = requireRequest(requestId, clientId);
        String status = (String) request.get("status");
        if ("CANCELLED".equals(status)) {
            return get(requestId, userId);
        }
        if (!"OPEN".equals(status) || movedAny(requestId)) {
            throw new BranchChangeRefusedException("Some of it has been moved already, so it can't be cancelled.");
        }
        UUID branchId = (UUID) request.get("branch_id");
        branchAccessService.requireMayRecordAt(userId, branchId);
        if (!userId.equals(request.get("asked_by"))
                && !areaAccessService.mayRecordIn(userId, branchId, (UUID) request.get("to_area_id"))) {
            throw new StockWriteNotUndoableException("Only the person who asked, or someone in the "
                    + areaName((UUID) request.get("to_area_id")) + ", can cancel this.");
        }
        jdbc.update("""
                UPDATE stock_requests SET status = 'CANCELLED', cancelled_by = :userId, cancelled_at = now() WHERE id = :id
                """, new MapSqlParameterSource().addValue("userId", userId).addValue("id", requestId));
        log.info("Stock request {} cancelled by {}", requestId, userId);
        String by = areaPeople.name(userId);
        pushGone(request, "Cancelled", by != null ? "Taken back by " + by : "Taken back");
        return get(requestId, userId);
    }

    @Transactional(readOnly = true)
    public StockRequestResponse get(UUID requestId, UUID userId) {
        UUID clientId = requireTenantId();
        requireRequest(requestId, clientId);
        List<StockRequestResponse> found = read("r.id = :id", new MapSqlParameterSource("id", requestId), userId, 0, 1);
        return found.getFirst();
    }

    /** Newest first, at the branches this person sees (one branch, when asked). */
    @Transactional(readOnly = true)
    public Page<StockRequestResponse> list(UUID userId, UUID branchId, int page, int size) {
        UUID clientId = requireTenantId();
        List<UUID> branches = branchAccessService.reportScope(userId, branchId);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("clientId", clientId)
                .addValue("branchIds", branches.toArray(UUID[]::new));
        String where = "r.client_id = :clientId AND r.branch_id = ANY(:branchIds)";
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM stock_requests r WHERE " + where, params, Long.class);
        return new PageImpl<>(read(where, params, userId, page * size, size), PageRequest.of(page, size),
                total == null ? 0 : total);
    }

    /** The open requests this person can fill: to an area they work in, at the branches they see. */
    @Transactional(readOnly = true)
    public List<StockRequestResponse> waitingFor(UUID userId, UUID branchId) {
        UUID clientId = requireTenantId();
        List<UUID> branches = branchAccessService.reportScope(userId, branchId);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("clientId", clientId)
                .addValue("branchIds", branches.toArray(UUID[]::new));
        return read("r.client_id = :clientId AND r.branch_id = ANY(:branchIds) AND r.status = 'OPEN'", params, userId, 0,
                100).stream()
                .filter(StockRequestResponse::canFill)
                // A line the store said it has none of is answered; only those still to move ask anything of it.
                .filter(request -> request.lines().stream().anyMatch(line -> !line.noneInStore() && line.moved() < line.quantity()))
                .toList();
    }

    /**
     * "The Kitchen needs 2 cartons of tomato paste. The Store has none." - for the Low stock page: lines the area
     * asked said it has none of, not moved since, on requests not cancelled, asked in the last two weeks.
     */
    @Transactional(readOnly = true)
    public List<NoneInStoreLine> noneInStoreLines(UUID userId, UUID branchId) {
        UUID clientId = requireTenantId();
        List<UUID> branches = branchAccessService.reportScope(userId, branchId);
        return jdbc.query("""
                SELECT r.id, r.branch_id, rl.product_id, p.name, p.unit_of_measure, rl.quantity - rl.quantity_moved AS left,
                       rl.entered_quantity, rl.entered_unit, rl.quantity, fa.name AS from_name, ta.name AS to_name,
                       r.asked_at, NULLIF(TRIM(COALESCE(u.first_name, '') || ' ' || COALESCE(u.last_name, '')), '') AS full_name,
                       u.username
                FROM stock_request_lines rl
                JOIN stock_requests r ON r.id = rl.request_id
                JOIN products p ON p.id = rl.product_id
                JOIN areas fa ON fa.id = r.from_area_id
                JOIN areas ta ON ta.id = r.to_area_id
                LEFT JOIN users u ON u.id = r.asked_by
                WHERE r.client_id = :clientId AND r.branch_id = ANY(:branchIds) AND r.status <> 'CANCELLED'
                  AND rl.none_in_store AND rl.quantity_moved < rl.quantity AND r.asked_at > :since
                ORDER BY r.asked_at DESC, p.name
                """, new MapSqlParameterSource()
                        .addValue("clientId", clientId)
                        .addValue("branchIds", branches.toArray(UUID[]::new))
                        .addValue("since", OffsetDateTime.now().minus(NONE_IN_STORE_SHOWN)),
                (rs, n) -> {
                    int left = rs.getInt("left");
                    // As typed, unless part of it was moved since.
                    boolean asTyped = left == rs.getInt("quantity");
                    String full = rs.getString("full_name");
                    return new NoneInStoreLine(
                            rs.getObject("id", UUID.class),
                            rs.getObject("branch_id", UUID.class),
                            rs.getObject("product_id", UUID.class),
                            rs.getString("name"),
                            rs.getString("unit_of_measure"),
                            left,
                            asTyped ? rs.getBigDecimal("entered_quantity") : null,
                            asTyped ? rs.getString("entered_unit") : null,
                            rs.getString("from_name"),
                            rs.getString("to_name"),
                            full != null ? full : rs.getString("username"),
                            rs.getObject("asked_at", OffsetDateTime.class));
                });
    }

    // ---------------------------------------------------------------------------------------- helpers

    /** Done once every line is moved or none in the store; open again if a filling move was undone. Its status now. */
    private String settle(UUID requestId) {
        jdbc.update("""
                UPDATE stock_requests SET status = CASE
                    WHEN NOT EXISTS (SELECT 1 FROM stock_request_lines
                                     WHERE request_id = :id AND NOT none_in_store AND quantity_moved < quantity)
                    THEN 'DONE' ELSE 'OPEN' END
                WHERE id = :id AND status <> 'CANCELLED'
                """, new MapSqlParameterSource("id", requestId));
        return jdbc.queryForObject("SELECT status FROM stock_requests WHERE id = :id", new MapSqlParameterSource("id", requestId),
                String.class);
    }

    /**
     * The request needs nothing more: on the movers' phones, the notification that asked is replaced by a quiet one
     * that says so and goes by itself ("if it has been moved it goes away").
     */
    private void pushGone(Map<String, Object> request, String title, String body) {
        UUID requestId = (UUID) request.get("id");
        pushService.notify(moverIds((UUID) request.get("from_area_id"), (UUID) request.get("branch_id"),
                (UUID) request.get("asked_by")), new PushMessage(
                        "The " + areaName((UUID) request.get("to_area_id")) + "'s request · " + title, body, tag(requestId),
                        link(requestId), true));
    }

    /** "The Store moved the 2 items you asked for" / "…some of what you asked for", to the asker - not themselves. */
    private void tellAskerMoved(Map<String, Object> request, UUID mover) {
        UUID requestId = (UUID) request.get("id");
        List<Map<String, Object>> lines = jdbc.queryForList(
                "SELECT quantity, quantity_moved, none_in_store FROM stock_request_lines WHERE request_id = :id",
                new MapSqlParameterSource("id", requestId));
        long whole = lines.stream()
                .filter(line -> ((Number) line.get("quantity_moved")).intValue() >= ((Number) line.get("quantity")).intValue())
                .count();
        String from = areaName((UUID) request.get("from_area_id"));
        String title = whole == lines.size()
                ? "The " + from + " moved " + (lines.size() == 1 ? "the item" : "the " + lines.size() + " items")
                        + " you asked for"
                : "The " + from + " moved some of what you asked for";
        String mover_ = areaPeople.name(mover);
        tellAsker(request, mover, title, (mover_ != null ? "Moved by " + mover_ + " · " : "")
                + "you asked at " + TIME.format(timestamp(request.get("asked_at"))));
    }

    private void tellAsker(Map<String, Object> request, UUID doneBy, String title, String body) {
        UUID asker = (UUID) request.get("asked_by");
        if (asker == null || asker.equals(doneBy)) {
            return;
        }
        notificationService.notifyClient((UUID) request.get("client_id"), asker, NotificationType.STOCK_REQUEST_ANSWERED,
                title, body, link((UUID) request.get("id")), null);
    }

    private boolean movedAny(UUID requestId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM stock_request_lines WHERE request_id = :id AND quantity_moved > 0)",
                new MapSqlParameterSource("id", requestId), Boolean.class));
    }

    private List<StockRequestResponse> read(String where, MapSqlParameterSource params, UUID userId, int offset, int limit) {
        params.addValue("offset", offset).addValue("limit", limit);
        List<Map<String, Object>> requests = jdbc.queryForList("""
                SELECT r.id, r.client_id, r.branch_id, r.from_area_id, fa.name AS from_name, r.to_area_id, ta.name AS to_name,
                       r.status, r.asked_by, r.asked_at, r.note,
                       NULLIF(TRIM(COALESCE(u.first_name, '') || ' ' || COALESCE(u.last_name, '')), '') AS full_name,
                       u.username
                FROM stock_requests r
                JOIN areas fa ON fa.id = r.from_area_id
                JOIN areas ta ON ta.id = r.to_area_id
                LEFT JOIN users u ON u.id = r.asked_by
                WHERE\s""" + where + """
                 ORDER BY r.asked_at DESC, r.created_at DESC
                OFFSET :offset LIMIT :limit
                """, params);
        if (requests.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<StockRequestResponse.Line>> linesByRequest = new LinkedHashMap<>();
        jdbc.query("""
                SELECT rl.request_id, rl.product_id, p.name, p.unit_of_measure, rl.quantity, rl.entered_quantity,
                       rl.entered_unit, rl.quantity_moved, rl.none_in_store
                FROM stock_request_lines rl JOIN products p ON p.id = rl.product_id
                WHERE rl.request_id = ANY(:ids) ORDER BY p.name
                """, new MapSqlParameterSource("ids", requests.stream().map(r -> (UUID) r.get("id")).toArray(UUID[]::new)),
                rs -> {
                    linesByRequest.computeIfAbsent(rs.getObject("request_id", UUID.class), key -> new ArrayList<>())
                            .add(new StockRequestResponse.Line(
                                    rs.getObject("product_id", UUID.class),
                                    rs.getString("name"),
                                    rs.getString("unit_of_measure"),
                                    rs.getInt("quantity"),
                                    rs.getBigDecimal("entered_quantity"),
                                    rs.getString("entered_unit"),
                                    rs.getInt("quantity_moved"),
                                    rs.getBoolean("none_in_store")));
                });
        // What this person may do with an open request: worked out once, not per request.
        boolean anyOpen = requests.stream().anyMatch(r -> "OPEN".equals(r.get("status")));
        boolean mayStockOut = anyOpen && userId != null && stockWritePermissionGuard.has(userId, "STOCK_OUT");
        AreaAccessService.Limits limits = anyOpen && userId != null ? areaAccessService.limits(userId) : null;
        List<StockRequestResponse> out = new ArrayList<>();
        for (Map<String, Object> r : requests) {
            UUID id = (UUID) r.get("id");
            UUID branchId = (UUID) r.get("branch_id");
            boolean open = "OPEN".equals(r.get("status"));
            List<StockRequestResponse.Line> lines = linesByRequest.getOrDefault(id, List.of());
            boolean canFill = open && mayStockOut && limits.worksIn(branchId, (UUID) r.get("from_area_id"))
                    && branchAccessService.mayRecordAt(userId, branchId);
            boolean canCancel = open && lines.stream().allMatch(line -> line.moved() == 0) && limits != null
                    && (userId.equals(r.get("asked_by")) || limits.worksIn(branchId, (UUID) r.get("to_area_id")));
            String name = r.get("full_name") != null ? (String) r.get("full_name") : (String) r.get("username");
            // Phone numbers only to the person who asked, and only while there's something to move.
            List<StockRequestResponse.Mover> movers = open && userId != null && userId.equals(r.get("asked_by"))
                    ? movers((UUID) r.get("from_area_id"), branchId, userId)
                    : List.of();
            out.add(new StockRequestResponse(id, branchId, (UUID) r.get("from_area_id"), (String) r.get("from_name"),
                    (UUID) r.get("to_area_id"), (String) r.get("to_name"), (String) r.get("status"),
                    (UUID) r.get("asked_by"), name, timestamp(r.get("asked_at")), (String) r.get("note"), lines, canFill,
                    canCancel, movers));
        }
        return out;
    }

    /**
     * Who can move what's asked of {@code areaId} (owner, 2026-11-01: "pick someone to send it to that has the ability
     * to move the stock"): the people of that area - else those in every area of the branch - who can record a
     * stock-out, with a WhatsApp number on their account. Never the asker.
     */
    private List<StockRequestResponse.Mover> movers(UUID areaId, UUID branchId, UUID askerId) {
        List<StockRequestResponse.Mover> found = new ArrayList<>();
        for (UUID person : moverIds(areaId, branchId, askerId)) {
            String phone = jdbc.queryForObject("SELECT phone FROM users WHERE id = :id", new MapSqlParameterSource("id", person),
                    String.class);
            WhatsAppNumbers.normalise(phone)
                    .ifPresent(number -> found.add(new StockRequestResponse.Mover(person, areaPeople.name(person), number)));
        }
        found.sort(Comparator.comparing(StockRequestResponse.Mover::name, String.CASE_INSENSITIVE_ORDER));
        return found;
    }

    /** The people of {@code areaId} (else everywhere-people) who can record a stock-out: who can move what it's asked. */
    private List<UUID> moverIds(UUID areaId, UUID branchId, UUID except) {
        return areaPeople.of(areaId, branchId, except).stream()
                .filter(person -> stockWritePermissionGuard.has(person, "STOCK_OUT"))
                .toList();
    }

    private boolean exists(UUID requestId, UUID clientId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM stock_requests WHERE id = :id AND client_id = :clientId)",
                new MapSqlParameterSource().addValue("id", requestId).addValue("clientId", clientId), Boolean.class));
    }

    private Map<String, Object> requireRequest(UUID requestId, UUID clientId) {
        List<Map<String, Object>> found = jdbc.queryForList("""
                SELECT id, client_id, branch_id, from_area_id, to_area_id, status, asked_by, asked_at
                FROM stock_requests WHERE id = :id AND client_id = :clientId
                """, new MapSqlParameterSource().addValue("id", requestId).addValue("clientId", clientId));
        if (found.isEmpty()) {
            throw new StockRequestNotFoundException();
        }
        return found.getFirst();
    }

    private AreaRow requireArea(UUID areaId, UUID branchId) {
        List<AreaRow> found = jdbc.query("""
                SELECT id, name, keeps_stock, is_active FROM areas WHERE id = :id AND branch_id = :branchId
                """, new MapSqlParameterSource().addValue("id", areaId).addValue("branchId", branchId),
                (rs, n) -> new AreaRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getBoolean("keeps_stock"),
                        rs.getBoolean("is_active")));
        if (found.isEmpty()) {
            throw new AreaNotFoundException();
        }
        return found.getFirst();
    }

    private String areaName(UUID areaId) {
        return jdbc.queryForObject("SELECT name FROM areas WHERE id = :id", new MapSqlParameterSource("id", areaId),
                String.class);
    }

    private static String link(UUID requestId) {
        return "/app?request=" + requestId;
    }

    private static String tag(UUID requestId) {
        return "request-" + requestId;
    }

    private static String itemsWord(int count) {
        return count == 1 ? "1 item" : count + " items";
    }

    /** A timestamptz as {@code queryForList} hands it back (a {@link java.sql.Timestamp}), as an instant in UTC. */
    private static OffsetDateTime timestamp(Object value) {
        if (value instanceof OffsetDateTime offset) {
            return offset;
        }
        return ((java.sql.Timestamp) value).toInstant().atOffset(java.time.ZoneOffset.UTC);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static UUID requireTenantId() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context is set");
        }
        return tenantId;
    }
}
