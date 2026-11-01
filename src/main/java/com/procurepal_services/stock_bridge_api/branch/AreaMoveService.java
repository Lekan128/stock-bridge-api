package com.procurepal_services.stock_bridge_api.branch;

import com.procurepal_services.stock_bridge_api.branch.BranchStockService.AreaUse;
import com.procurepal_services.stock_bridge_api.branch.dto.AreaMoveConfirmRequest;
import com.procurepal_services.stock_bridge_api.branch.dto.AreaMoveRequest;
import com.procurepal_services.stock_bridge_api.branch.dto.AreaMoveResponse;
import com.procurepal_services.stock_bridge_api.entity.NotificationType;
import com.procurepal_services.stock_bridge_api.entity.OutReason;
import com.procurepal_services.stock_bridge_api.notification.NotificationService;
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
import java.util.HashMap;
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
 * Moving stock between the areas of a branch (STOCK_AREAS_PART_2.md, STOCK_AREAS_PLAN.md §5.1): Store →
 * Kitchen in one write, a give to an area that uses what it's given (Housekeeping) as a Used stock-out
 * there, Undo for two minutes, and the list Stock movements' Moves filter reads.
 *
 * <p>A move is its own record, not a ledger movement (STOCK_AREAS_BUILD_PLAN.md D-E5): it changes no
 * branch figure, lot or cost. Its id is made on the phone (D-E11), so the outbox can resend it safely.
 * Who may: anyone who can record a stock-out (D-A8), at a branch they work at now - checked from the
 * database on every write, like every stock write.
 *
 * <p>To an area that confirms what arrives (Part 4, STOCK_AREAS_PART_4.md, D-A2): the lines leave the sending
 * area at once and wait - in the branch's figure, in no area's - until someone there confirms (what didn't
 * arrive goes back, or is recorded as damaged: D-A9) or the move is cancelled.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class AreaMoveService {

    /** Undo, as for every stock write (D8): the mover, for two minutes. */
    static final Duration UNDO_WINDOW = Duration.ofMinutes(2);

    /** "7:42 AM" in a notification: the time of day where the business is. */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
            .withZone(PayoutCadence.ZONE);

    private final NamedParameterJdbcTemplate jdbc;
    private final BranchStockService branchStockService;
    private final BranchAccessService branchAccessService;
    private final StockManagementService stockManagementService;
    private final StockWritePermissionGuard stockWritePermissionGuard;
    private final AreaAccessService areaAccessService;
    private final NotificationService notificationService;
    private final AreaPeople areaPeople;
    private final AreaReturns areaReturns;
    private final StockRequestService stockRequestService;

    /** One area as a move needs it. */
    private record AreaRow(UUID id, String name, boolean keepsStock, boolean active, boolean confirms) {
    }

    public AreaMoveResponse move(AreaMoveRequest request, UUID userId) {
        UUID clientId = requireTenantId();
        // Sent again after a lost reply, or twice by a phone that came back online: the first one stands.
        if (exists(request.id(), clientId)) {
            return get(request.id(), userId);
        }
        stockWritePermissionGuard.require(userId, "STOCK_OUT");
        UUID branchId = branchStockService.resolveForWrite(request.branchId(), userId);
        branchAccessService.requireMayRecordAt(userId, branchId);
        UUID fromAreaId = branchStockService.resolveAreaForWrite(branchId, request.fromAreaId(), AreaUse.TAKE, userId);
        AreaRow to = requireArea(request.toAreaId(), branchId);
        if (!to.active()) {
            throw new AreaClosedException(to.name());
        }
        if (to.id().equals(fromAreaId)) {
            throw new BranchChangeRefusedException("Choose two different areas: this would move it to where it already is.");
        }
        Set<UUID> seen = new HashSet<>();
        for (AreaMoveRequest.Line line : request.lines()) {
            if (!seen.add(line.productId())) {
                throw new BranchChangeRefusedException("Each product goes on a move once.");
            }
        }

        OffsetDateTime movedAt = request.occurredAt() != null ? request.occurredAt() : OffsetDateTime.now();
        boolean late = Boolean.TRUE.equals(request.recordedOffline());
        // To an area that confirms (Part 4): handed over now, in its figure once someone there says so.
        boolean waits = to.confirms();
        // Filling a request (Part 5): counted towards it while it's open and this is its route.
        UUID requestId = request.requestId() != null
                && stockRequestService.fillable(request.requestId(), branchId, fromAreaId, to.id()) ? request.requestId() : null;
        jdbc.update("""
                INSERT INTO area_moves (id, client_id, branch_id, from_area_id, to_area_id, status, waits, moved_by,
                                        moved_at, recorded_offline, note, request_id)
                VALUES (:id, :clientId, :branchId, :fromAreaId, :toAreaId, :status, :waits, :userId, :movedAt, :late,
                        :note, :requestId)
                """, new MapSqlParameterSource()
                        .addValue("requestId", requestId)
                        .addValue("id", request.id())
                        .addValue("clientId", clientId)
                        .addValue("branchId", branchId)
                        .addValue("fromAreaId", fromAreaId)
                        .addValue("toAreaId", to.id())
                        .addValue("status", waits ? "WAITING" : "DONE")
                        .addValue("waits", waits)
                        .addValue("userId", userId)
                        .addValue("movedAt", movedAt)
                        .addValue("late", late)
                        .addValue("note", blankToNull(request.note())));

        String usedNote = blankToNull(request.note()) != null ? "Given to " + to.name() + ": " + request.note().trim()
                : "Given to " + to.name();
        // Products in id order: the one lock order every stock write takes.
        List<AreaMoveRequest.Line> lines = new ArrayList<>(request.lines());
        lines.sort(Comparator.comparing(AreaMoveRequest.Line::productId));
        for (AreaMoveRequest.Line line : lines) {
            // Used straight away (Part 3.5): a give to an area that otherwise keeps it, for this line alone.
            boolean usedNow = to.keepsStock() && Boolean.TRUE.equals(line.usedNow());
            StockManagementService.AreaMovePiece piece = waits
                    ? stockManagementService.holdForMove(line.productId(), branchId, fromAreaId, line.quantity(),
                            blankToNull(line.unit()), movedAt, late, userId)
                    : stockManagementService.moveBetweenAreas(
                            line.productId(), branchId, fromAreaId, to.id(), to.keepsStock() && !usedNow, line.quantity(),
                            blankToNull(line.unit()), movedAt, late,
                            usedNow ? "Used straight away at " + to.name() : usedNote, userId);
            jdbc.update("""
                    INSERT INTO area_move_lines (move_id, product_id, quantity, entered_unit, entered_quantity,
                                                 used_movement_id, used_now)
                    VALUES (:moveId, :productId, :quantity, :enteredUnit, :enteredQuantity, :usedMovementId, :usedNow)
                    """, new MapSqlParameterSource()
                            .addValue("moveId", request.id())
                            .addValue("productId", line.productId())
                            .addValue("quantity", piece.quantity())
                            .addValue("enteredUnit", piece.enteredUnit())
                            .addValue("enteredQuantity", piece.enteredQuantity())
                            .addValue("usedMovementId", piece.usedMovementId())
                            .addValue("usedNow", usedNow));
        }
        log.info("Area move {}: {} lines, area {} -> {} at branch {} by {}{}", request.id(), lines.size(), fromAreaId,
                to.id(), branchId, userId, waits ? ", waiting to be confirmed" : "");
        if (waits) {
            notifyWaiting(clientId, request.id(), branchId, fromAreaId, to, lines.size(), movedAt, userId);
        }
        if (requestId != null) {
            stockRequestService.recount(requestId, userId);
        }
        return get(request.id(), userId);
    }

    /**
     * Confirms what arrived of a move waiting for an area that confirms (Part 4): anyone who works in that area
     * and can record stock in. What arrived goes into its figure (or, where it's used there, is recorded as used);
     * the rest goes back to where it was moved from, or is recorded as damaged there (D-A9). Sent twice - after a
     * lost reply, or late from a phone that was offline - the first stands.
     */
    public AreaMoveResponse confirm(UUID moveId, AreaMoveConfirmRequest request, UUID userId) {
        UUID clientId = requireTenantId();
        Map<String, Object> move = requireMove(moveId, clientId);
        String status = (String) move.get("status");
        if ("DONE".equals(status) || "CONFIRMED_SHORT".equals(status)) {
            return get(moveId, userId);
        }
        if (!"WAITING".equals(status)) {
            throw new BranchChangeRefusedException("CANCELLED".equals(status) || "VOIDED".equals(status)
                    ? "This move was taken back, so there's nothing to confirm."
                    : "This move doesn't need confirming.");
        }
        stockWritePermissionGuard.require(userId, "STOCK_IN");
        UUID branchId = (UUID) move.get("branch_id");
        branchAccessService.requireMayRecordAt(userId, branchId);
        UUID fromAreaId = (UUID) move.get("from_area_id");
        AreaRow to = requireArea((UUID) move.get("to_area_id"), branchId);
        areaAccessService.requireMayRecordIn(userId, branchId, to.id());
        String fromName = requireArea(fromAreaId, branchId).name();

        Map<UUID, Integer> got = new HashMap<>();
        Map<UUID, AreaMoveConfirmRequest.Rest> restOf = new HashMap<>();
        for (AreaMoveConfirmRequest.Line line : request.lines() == null ? List.<AreaMoveConfirmRequest.Line>of() : request.lines()) {
            if (got.put(line.productId(), line.got()) != null) {
                throw new BranchChangeRefusedException("Each product is confirmed once.");
            }
            if (line.rest() != null) {
                restOf.put(line.productId(), line.rest());
            }
        }
        // What happened to each short line (Part 6): its own answer, else the move's, else still where it was.
        AreaMoveConfirmRequest.Rest moveRest = request.rest() != null ? request.rest() : AreaMoveConfirmRequest.Rest.BACK;
        String note = blankToNull(request.note());
        OffsetDateTime at = request.occurredAt() != null ? request.occurredAt() : OffsetDateTime.now();
        boolean late = Boolean.TRUE.equals(request.recordedOffline());
        List<Map<String, Object>> lines = jdbc.queryForList("""
                SELECT product_id, quantity, used_now FROM area_move_lines WHERE move_id = :id ORDER BY product_id
                """, new MapSqlParameterSource("id", moveId));
        for (UUID productId : got.keySet()) {
            if (lines.stream().noneMatch(line -> productId.equals(line.get("product_id")))) {
                throw new BranchChangeRefusedException("That product isn't on this move.");
            }
        }
        int short_ = 0;
        Map<AreaMoveConfirmRequest.Rest, Integer> restTotals = new java.util.EnumMap<>(AreaMoveConfirmRequest.Rest.class);
        for (Map<String, Object> line : lines) {
            UUID productId = (UUID) line.get("product_id");
            int quantity = ((Number) line.get("quantity")).intValue();
            int arrived = got.getOrDefault(productId, quantity);
            if (arrived > quantity) {
                throw new BranchChangeRefusedException("More can't arrive than was moved: " + quantity + " were moved.");
            }
            boolean keep = to.keepsStock() && !Boolean.TRUE.equals(line.get("used_now"));
            int rest = quantity - arrived;
            AreaMoveConfirmRequest.Rest why = rest > 0 ? restOf.getOrDefault(productId, moveRest) : null;
            OutReason writtenOff = why == null ? null : why.outReason();
            StockManagementService.SettledLine settled = stockManagementService.settleWaitingLine(
                    productId, branchId, fromAreaId, to.id(), quantity, arrived, keep, writtenOff, at, late,
                    "Given to " + to.name(), restNote(why, to.name(), note), userId);
            short_ += rest;
            if (why != null) {
                restTotals.merge(why, rest, Integer::sum);
            }
            jdbc.update("""
                    UPDATE area_move_lines SET quantity_confirmed = :got, quantity_damaged = :damaged,
                                               used_movement_id = :used, rest_reason = :why
                    WHERE move_id = :id AND product_id = :productId
                    """, new MapSqlParameterSource()
                            .addValue("got", arrived)
                            .addValue("damaged", writtenOff != null ? rest : 0)
                            .addValue("why", why == null ? null : why.name())
                            .addValue("used", settled.usedMovementId())
                            .addValue("id", moveId)
                            .addValue("productId", productId));
        }
        jdbc.update("""
                UPDATE area_moves SET status = :status, confirmed_by = :userId, confirmed_at = :at, confirm_note = :note
                WHERE id = :id
                """, new MapSqlParameterSource()
                        .addValue("note", note)
                        .addValue("status", short_ > 0 ? "CONFIRMED_SHORT" : "DONE")
                        .addValue("userId", userId)
                        .addValue("at", at)
                        .addValue("id", moveId));
        log.info("Area move {} confirmed by {}: {} short ({})", moveId, userId, short_, restTotals);
        if (short_ > 0) {
            notifyShort(clientId, moveId, fromName, to.name(), lines, got, restTotals, note, (UUID) move.get("moved_by"),
                    userId);
        }
        return get(moveId, userId);
    }

    /**
     * Takes back a move still waiting to be confirmed (Part 4): its mover, or anyone who works where it was moved
     * from and can record a stock-out there (the Store's people; managers work everywhere). Every line goes back
     * to where it was moved from. Online only.
     */
    public AreaMoveResponse cancel(UUID moveId, UUID userId) {
        UUID clientId = requireTenantId();
        Map<String, Object> move = requireMove(moveId, clientId);
        String status = (String) move.get("status");
        if ("CANCELLED".equals(status)) {
            return get(moveId, userId);
        }
        if (!"WAITING".equals(status)) {
            throw new BranchChangeRefusedException("It's been confirmed, so it can't be taken back. Move it back instead.");
        }
        UUID branchId = (UUID) move.get("branch_id");
        branchAccessService.requireMayRecordAt(userId, branchId);
        if (!userId.equals(move.get("moved_by")) && !(stockWritePermissionGuard.has(userId, "STOCK_OUT")
                && areaAccessService.mayRecordIn(userId, branchId, (UUID) move.get("from_area_id")))) {
            throw new StockWriteNotUndoableException("Only the person who moved this, or someone who works in the "
                    + requireArea((UUID) move.get("from_area_id"), branchId).name() + ", can take it back.");
        }
        releaseHolds(moveId, branchId, (UUID) move.get("from_area_id"), (UUID) move.get("to_area_id"), userId);
        jdbc.update("""
                UPDATE area_moves SET status = 'CANCELLED', cancelled_by = :userId, cancelled_at = now() WHERE id = :id
                """, new MapSqlParameterSource().addValue("userId", userId).addValue("id", moveId));
        recountRequest(move);
        log.info("Area move {} cancelled by {}", moveId, userId);
        return get(moveId, userId);
    }

    /**
     * What arrived in the last {@link AreaReturns#WINDOW} in the areas this person works in (Part 6), newest first -
     * what they may give back or report part of. Moves that were themselves a give back aren't offered again.
     */
    @Transactional(readOnly = true)
    public List<AreaMoveResponse> receivedBy(UUID userId, UUID branchId) {
        UUID clientId = requireTenantId();
        List<UUID> branches = branchAccessService.reportScope(userId, branchId);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("clientId", clientId)
                .addValue("branchIds", branches.toArray(UUID[]::new))
                .addValue("since", OffsetDateTime.now().minus(AreaReturns.WINDOW));
        return read("m.client_id = :clientId AND m.branch_id = ANY(:branchIds) AND m.gives_back_move_id IS NULL"
                + " AND m.status IN ('DONE', 'CONFIRMED_SHORT') AND m.moved_at > :since", params, userId, 0, 100)
                .stream().filter(AreaMoveResponse::canReturn).toList();
    }

    /** The moves waiting for this person to confirm (Part 4): to areas they work in, at the branches they see. */
    @Transactional(readOnly = true)
    public List<AreaMoveResponse> waitingFor(UUID userId, UUID branchId) {
        UUID clientId = requireTenantId();
        List<UUID> branches = branchAccessService.reportScope(userId, branchId);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("clientId", clientId)
                .addValue("branchIds", branches.toArray(UUID[]::new));
        return read("m.client_id = :clientId AND m.branch_id = ANY(:branchIds) AND m.status = 'WAITING'", params, userId,
                0, 100).stream().filter(AreaMoveResponse::canConfirm).toList();
    }

    /**
     * Undo (D8): the mover, within two minutes, while nothing else has been recorded for its products -
     * every line goes back where it came from, a give's Used stock-out is voided like any stock-out, and
     * the move stays in history as undone. A move sent late from an offline phone may have met a later
     * count, so it is moved back instead, like any write the undo can't safely take back.
     */
    public AreaMoveResponse undo(UUID moveId, UUID userId) {
        UUID clientId = requireTenantId();
        Map<String, Object> move = requireMove(moveId, clientId);
        if ("VOIDED".equals(move.get("status"))) {
            return get(moveId, userId);
        }
        if (!userId.equals(move.get("moved_by"))) {
            throw new StockWriteNotUndoableException("Only the person who moved this can undo it.");
        }
        OffsetDateTime createdAt = timestamp(move.get("created_at"));
        if (createdAt.isBefore(OffsetDateTime.now().minus(UNDO_WINDOW))) {
            throw new StockWriteNotUndoableException("It's too late to undo this. Move it back instead.");
        }
        if (Boolean.TRUE.equals(move.get("recorded_offline"))) {
            throw new StockWriteNotUndoableException(
                    "This was moved on a phone while offline, so it can't be undone. Move it back instead.");
        }
        stockWritePermissionGuard.require(userId, "STOCK_OUT");
        UUID branchId = (UUID) move.get("branch_id");
        UUID fromAreaId = (UUID) move.get("from_area_id");
        UUID toAreaId = (UUID) move.get("to_area_id");
        if (Boolean.TRUE.equals(move.get("waits"))) {
            // Still waiting: nothing arrived anywhere, so the lines simply go back (Part 4). Once confirmed, it's
            // moved back like any move.
            if (!"WAITING".equals(move.get("status"))) {
                throw new StockWriteNotUndoableException("It's been confirmed, so it can't be undone. Move it back instead.");
            }
            releaseHolds(moveId, branchId, fromAreaId, toAreaId, userId);
            jdbc.update("UPDATE area_moves SET status = 'VOIDED' WHERE id = :id", new MapSqlParameterSource("id", moveId));
            recountRequest(move);
            log.info("Area move {} undone by {} while waiting", moveId, userId);
            return get(moveId, userId);
        }
        List<Map<String, Object>> lines = jdbc.queryForList("""
                SELECT product_id, quantity, used_movement_id FROM area_move_lines WHERE move_id = :id ORDER BY product_id
                """, new MapSqlParameterSource("id", moveId));
        if (somethingRecordedSince(moveId, branchId, createdAt, lines)) {
            throw new StockWriteNotUndoableException(
                    "Something else has been recorded for these products since, so this can't be undone. Move it back instead.");
        }

        for (Map<String, Object> line : lines) {
            UUID productId = (UUID) line.get("product_id");
            UUID usedMovementId = (UUID) line.get("used_movement_id");
            if (usedMovementId != null) {
                // The stock-out row is voided (removed, as Undo does): the line lets go of it first.
                jdbc.update("UPDATE area_move_lines SET used_movement_id = NULL WHERE move_id = :id AND product_id = :p",
                        new MapSqlParameterSource().addValue("id", moveId).addValue("p", productId));
                stockManagementService.voidWrite(productId, usedMovementId, userId);
            } else {
                stockManagementService.moveAreaLineBack(productId, branchId, fromAreaId, toAreaId,
                        ((Number) line.get("quantity")).intValue());
            }
        }
        jdbc.update("UPDATE area_moves SET status = 'VOIDED' WHERE id = :id", new MapSqlParameterSource("id", moveId));
        recountRequest(move);
        log.info("Area move {} undone by {}", moveId, userId);
        return get(moveId, userId);
    }

    @Transactional(readOnly = true)
    public AreaMoveResponse get(UUID moveId, UUID userId) {
        UUID clientId = requireTenantId();
        requireMove(moveId, clientId);
        List<AreaMoveResponse> found = read("m.id = :id", new MapSqlParameterSource("id", moveId), userId, 0, 1);
        if (found.isEmpty()) {
            throw new AreaNotFoundException();
        }
        return found.getFirst();
    }

    /** Newest first, at the branches this person sees; one branch, or one product's moves, when asked. */
    @Transactional(readOnly = true)
    public Page<AreaMoveResponse> list(UUID userId, UUID branchId, UUID productId, int page, int size) {
        UUID clientId = requireTenantId();
        List<UUID> branches = branchAccessService.reportScope(userId, branchId);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("clientId", clientId)
                .addValue("branchIds", branches.toArray(UUID[]::new))
                .addValue("productId", productId);
        String where = "m.client_id = :clientId AND m.branch_id = ANY(:branchIds)"
                + (productId == null ? "" : " AND EXISTS (SELECT 1 FROM area_move_lines pl WHERE pl.move_id = m.id AND pl.product_id = :productId)");
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM area_moves m WHERE " + where, params, Long.class);
        List<AreaMoveResponse> content = read(where, params, userId, page * size, size);
        return new PageImpl<>(content, PageRequest.of(page, size), total == null ? 0 : total);
    }

    // ---------------------------------------------------------------------------------------- helpers

    /** A move that filled a request (Part 5) was undone or taken back: the request asks for it again. */
    private void recountRequest(Map<String, Object> move) {
        if (move.get("request_id") != null) {
            stockRequestService.recount((UUID) move.get("request_id"), null);
        }
    }

    /** A waiting move's lines back where they were moved from: cancelled, or undone. */
    private void releaseHolds(UUID moveId, UUID branchId, UUID fromAreaId, UUID toAreaId, UUID userId) {
        List<Map<String, Object>> lines = jdbc.queryForList(
                "SELECT product_id, quantity FROM area_move_lines WHERE move_id = :id ORDER BY product_id",
                new MapSqlParameterSource("id", moveId));
        for (Map<String, Object> line : lines) {
            stockManagementService.settleWaitingLine((UUID) line.get("product_id"), branchId, fromAreaId, toAreaId,
                    ((Number) line.get("quantity")).intValue(), 0, false, null, null, false, null, null, userId);
        }
    }

    /**
     * "2 items from the Store to confirm", to the people placed in the area it waits for - or, when nobody is
     * placed there, to the people who work in every area of the branch (managers, owners), so a move never
     * waits unseen. Never to the person who moved it.
     */
    private void notifyWaiting(UUID clientId, UUID moveId, UUID branchId, UUID fromAreaId, AreaRow to, int items,
            OffsetDateTime movedAt, UUID moverId) {
        List<UUID> people = areaPeople.of(to.id(), branchId, moverId);
        String fromName = requireArea(fromAreaId, branchId).name();
        String mover = areaPeople.name(moverId);
        String title = (items == 1 ? "1 item" : items + " items") + " from the " + fromName + " to confirm";
        String body = "Moved to the " + to.name() + (mover != null ? " by " + mover : "") + " at " + TIME.format(movedAt);
        for (UUID person : people) {
            notificationService.notifyClient(clientId, person, NotificationType.AREA_MOVE_WAITING, title, body,
                    "/app?move=" + moveId, null);
        }
    }

    /** "Kitchen got 2 of 3 from the Store", to the mover and the account holders - not the person who confirmed. */
    private void notifyShort(UUID clientId, UUID moveId, String fromName, String toName, List<Map<String, Object>> lines,
            Map<UUID, Integer> got, Map<AreaMoveConfirmRequest.Rest, Integer> restTotals, String note, UUID moverId,
            UUID confirmerId) {
        Set<UUID> people = new HashSet<>(jdbc.queryForList(
                "SELECT id FROM users WHERE client_id = :clientId AND is_root AND is_active",
                new MapSqlParameterSource("clientId", clientId), UUID.class));
        if (moverId != null) {
            people.add(moverId);
        }
        people.remove(confirmerId);
        int moved = lines.stream().mapToInt(line -> ((Number) line.get("quantity")).intValue()).sum();
        int arrived = lines.stream()
                .mapToInt(line -> got.getOrDefault((UUID) line.get("product_id"), ((Number) line.get("quantity")).intValue()))
                .sum();
        String title = "The " + toName + " got " + arrived + " of " + moved + " from the " + fromName;
        // "1 still in the Store · 1 damaged or spoilt · Two bags torn on the stairs" (Part 6).
        List<String> parts = new ArrayList<>();
        restTotals.forEach((why, count) -> parts.add(count + " " + restWords(why, fromName)));
        if (note != null) {
            parts.add(note);
        }
        String body = String.join(" · ", parts);
        for (UUID person : people) {
            notificationService.notifyClient(clientId, person, NotificationType.AREA_MOVE_SHORT, title, body,
                    "/app?move=" + moveId, null);
        }
    }

    private List<AreaMoveResponse> read(String where, MapSqlParameterSource params, UUID userId, int offset, int limit) {
        params.addValue("offset", offset).addValue("limit", limit);
        List<Map<String, Object>> moves = jdbc.queryForList("""
                SELECT m.id, m.branch_id, m.from_area_id, fa.name AS from_name, m.to_area_id, ta.name AS to_name,
                       ta.keeps_stock AS to_keeps_stock, m.status, m.waits, m.moved_by, m.moved_at, m.note, m.created_at,
                       m.recorded_offline, m.confirmed_at, m.confirm_note, m.gives_back_move_id,
                       NULLIF(TRIM(COALESCE(u.first_name, '') || ' ' || COALESCE(u.last_name, '')), '') AS full_name,
                       u.username,
                       NULLIF(TRIM(COALESCE(c.first_name, '') || ' ' || COALESCE(c.last_name, '')), '') AS confirmed_full_name,
                       c.username AS confirmed_username
                FROM area_moves m
                JOIN areas fa ON fa.id = m.from_area_id
                JOIN areas ta ON ta.id = m.to_area_id
                LEFT JOIN users u ON u.id = m.moved_by
                LEFT JOIN users c ON c.id = m.confirmed_by
                WHERE\s""" + where + """
                 ORDER BY m.moved_at DESC, m.created_at DESC
                OFFSET :offset LIMIT :limit
                """, params);
        if (moves.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<AreaMoveResponse.Line>> linesByMove = new LinkedHashMap<>();
        jdbc.query("""
                SELECT l.move_id, l.product_id, p.name, l.quantity, l.entered_quantity, l.entered_unit,
                       l.used_movement_id IS NOT NULL OR l.used_now AS used, l.quantity_confirmed, l.quantity_damaged,
                       p.unit_of_measure, l.rest_reason
                FROM area_move_lines l JOIN products p ON p.id = l.product_id
                WHERE l.move_id = ANY(:ids) ORDER BY p.name
                """, new MapSqlParameterSource("ids", moves.stream().map(m -> (UUID) m.get("id")).toArray(UUID[]::new)),
                rs -> {
                    linesByMove.computeIfAbsent(rs.getObject("move_id", UUID.class), key -> new ArrayList<>())
                            .add(new AreaMoveResponse.Line(
                                    rs.getObject("product_id", UUID.class),
                                    rs.getString("name"),
                                    rs.getInt("quantity"),
                                    rs.getBigDecimal("entered_quantity"),
                                    rs.getString("entered_unit"),
                                    rs.getBoolean("used"),
                                    rs.getObject("quantity_confirmed", Integer.class),
                                    rs.getInt("quantity_damaged"),
                                    rs.getString("unit_of_measure"),
                                    rs.getString("rest_reason"),
                                    List.of(),
                                    0));
                });
        Map<UUID, Map<UUID, List<AreaMoveResponse.Return>>> returns = areaReturns.byMove(
                moves.stream().map(m -> (UUID) m.get("id")).toList());
        AreaAccessService.Limits returnLimits = userId != null && areaReturns.mayRecord(userId)
                ? areaAccessService.limits(userId) : null;
        // What this person may do with a move that waits: worked out once, not per move.
        boolean anyWaiting = moves.stream().anyMatch(m -> "WAITING".equals(m.get("status")));
        boolean mayStockIn = anyWaiting && userId != null && stockWritePermissionGuard.has(userId, "STOCK_IN");
        boolean mayStockOut = anyWaiting && userId != null && stockWritePermissionGuard.has(userId, "STOCK_OUT");
        AreaAccessService.Limits limits = anyWaiting && userId != null ? areaAccessService.limits(userId) : null;
        List<AreaMoveResponse> out = new ArrayList<>();
        for (Map<String, Object> m : moves) {
            UUID id = (UUID) m.get("id");
            boolean toKeepsStock = Boolean.TRUE.equals(m.get("to_keeps_stock"));
            OffsetDateTime createdAt = timestamp(m.get("created_at"));
            boolean waits = Boolean.TRUE.equals(m.get("waits"));
            boolean waiting = "WAITING".equals(m.get("status"));
            boolean undoable = (waits ? waiting : "DONE".equals(m.get("status"))) && userId != null
                    && userId.equals(m.get("moved_by")) && !Boolean.TRUE.equals(m.get("recorded_offline"))
                    && createdAt.isAfter(OffsetDateTime.now().minus(UNDO_WINDOW));
            String name = m.get("full_name") != null ? (String) m.get("full_name") : (String) m.get("username");
            String confirmedBy = m.get("confirmed_full_name") != null ? (String) m.get("confirmed_full_name")
                    : (String) m.get("confirmed_username");
            UUID branchId = (UUID) m.get("branch_id");
            boolean canConfirm = waiting && mayStockIn && limits.worksIn(branchId, (UUID) m.get("to_area_id"))
                    && branchAccessService.mayRecordAt(userId, branchId);
            boolean canCancel = waiting && userId != null && (userId.equals(m.get("moved_by"))
                    || (mayStockOut && limits.worksIn(branchId, (UUID) m.get("from_area_id"))));
            out.add(new AreaMoveResponse(
                    id,
                    (UUID) m.get("branch_id"),
                    (UUID) m.get("from_area_id"),
                    (String) m.get("from_name"),
                    (UUID) m.get("to_area_id"),
                    (String) m.get("to_name"),
                    toKeepsStock,
                    (String) m.get("status"),
                    (UUID) m.get("moved_by"),
                    name,
                    timestamp(m.get("moved_at")),
                    (String) m.get("note"),
                    undoable ? createdAt.plus(UNDO_WINDOW) : null,
                    // A give's lines were Used there (D-E7): said by the area it went to, or by the line when it
                    // was used straight away (Part 3.5).
                    linesByMove.getOrDefault(id, List.of()).stream()
                            .map(line -> {
                                List<AreaMoveResponse.Return> back = returns.getOrDefault(id, Map.of())
                                        .getOrDefault(line.productId(), List.of());
                                int used = line.got() != null ? line.got() : line.quantity();
                                return new AreaMoveResponse.Line(line.productId(), line.productName(), line.quantity(),
                                        line.enteredQuantity(), line.enteredUnit(), !toKeepsStock || line.used(), line.got(),
                                        line.damaged(), line.unitOfMeasure(), line.restReason(), back,
                                        Math.max(0, used - back.stream().mapToInt(AreaMoveResponse.Return::quantity).sum()));
                            })
                            .toList(),
                    waits,
                    confirmedBy,
                    m.get("confirmed_at") == null ? null : timestamp(m.get("confirmed_at")),
                    canConfirm,
                    canCancel,
                    (String) m.get("confirm_note"),
                    returnLimits != null && m.get("gives_back_move_id") == null
                            && ("DONE".equals(m.get("status")) || "CONFIRMED_SHORT".equals(m.get("status")))
                            && timestamp(m.get("moved_at")).isAfter(OffsetDateTime.now().minus(AreaReturns.WINDOW))
                            && returnLimits.worksIn(branchId, (UUID) m.get("to_area_id"))
                            && branchAccessService.mayRecordAt(userId, branchId)));
        }
        return out;
    }

    /** Anything recorded for this move's products since it was made, at its branch: a stock write or another move. */
    private boolean somethingRecordedSince(UUID moveId, UUID branchId, OffsetDateTime since, List<Map<String, Object>> lines) {
        UUID[] products = lines.stream().map(line -> (UUID) line.get("product_id")).toArray(UUID[]::new);
        UUID[] own = lines.stream().map(line -> (UUID) line.get("used_movement_id")).filter(java.util.Objects::nonNull)
                .toArray(UUID[]::new);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("moveId", moveId)
                .addValue("branchId", branchId)
                .addValue("since", since)
                .addValue("products", products)
                .addValue("own", own);
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM stock_movements
                               WHERE branch_id = :branchId AND product_id = ANY(:products) AND created_at > :since
                                 AND NOT (id = ANY(:own)))
                    OR EXISTS (SELECT 1 FROM area_moves m JOIN area_move_lines l ON l.move_id = m.id
                               WHERE m.branch_id = :branchId AND l.product_id = ANY(:products) AND m.id <> :moveId
                                 AND m.created_at > :since AND m.status <> 'VOIDED')
                """, params, Boolean.class));
    }

    private boolean exists(UUID moveId, UUID clientId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM area_moves WHERE id = :id AND client_id = :clientId)",
                new MapSqlParameterSource().addValue("id", moveId).addValue("clientId", clientId), Boolean.class));
    }

    private Map<String, Object> requireMove(UUID moveId, UUID clientId) {
        List<Map<String, Object>> found = jdbc.queryForList("""
                SELECT id, branch_id, from_area_id, to_area_id, status, waits, moved_by, created_at, recorded_offline,
                       request_id
                FROM area_moves WHERE id = :id AND client_id = :clientId
                """, new MapSqlParameterSource().addValue("id", moveId).addValue("clientId", clientId));
        if (found.isEmpty()) {
            throw new AreaMoveNotFoundException();
        }
        return found.getFirst();
    }

    private AreaRow requireArea(UUID areaId, UUID branchId) {
        List<AreaRow> found = jdbc.query("""
                SELECT id, name, keeps_stock, is_active, confirms FROM areas WHERE id = :id AND branch_id = :branchId
                """, new MapSqlParameterSource().addValue("id", areaId).addValue("branchId", branchId),
                (rs, n) -> new AreaRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getBoolean("keeps_stock"),
                        rs.getBoolean("is_active"), rs.getBoolean("confirms")));
        if (found.isEmpty()) {
            throw new AreaNotFoundException();
        }
        return found.getFirst();
    }

    /** A timestamptz as {@code queryForList} hands it back (a {@link java.sql.Timestamp}), as an instant in UTC. */
    private static OffsetDateTime timestamp(Object value) {
        if (value instanceof OffsetDateTime offset) {
            return offset;
        }
        return ((java.sql.Timestamp) value).toInstant().atOffset(java.time.ZoneOffset.UTC);
    }

    /** What happened to a short line, said after its count: "still in the Store", "damaged or spoilt", "missing". */
    static String restWords(AreaMoveConfirmRequest.Rest why, String fromName) {
        return switch (why) {
            case BACK -> "still in the " + fromName;
            case DAMAGED -> "damaged or spoilt";
            case EXPIRED -> "expired";
            case LOST -> "missing";
            case OTHER -> "not arrived, for another reason";
        };
    }

    /** The note on the stock-out a short line is written off as: why, where it was going, and the confirmer's words. */
    private static String restNote(AreaMoveConfirmRequest.Rest why, String toName, String note) {
        if (why == null || why.outReason() == null) {
            return null;
        }
        String said = switch (why) {
            case DAMAGED -> "Damaged or spoilt on the way to " + toName;
            case EXPIRED -> "Expired, on the way to " + toName;
            case LOST -> "Missing on the way to " + toName;
            default -> "Didn't reach " + toName;
        };
        return note == null ? said : said + ": " + note;
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
