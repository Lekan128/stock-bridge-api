package com.procurepal_services.stock_bridge_api.branch;

import com.procurepal_services.stock_bridge_api.branch.BranchStockService.AreaUse;
import com.procurepal_services.stock_bridge_api.branch.dto.AreaMoveRequest;
import com.procurepal_services.stock_bridge_api.branch.dto.AreaMoveResponse;
import com.procurepal_services.stock_bridge_api.stock.StockManagementService;
import com.procurepal_services.stock_bridge_api.stock.StockWriteNotUndoableException;
import com.procurepal_services.stock_bridge_api.stock.StockWritePermissionGuard;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class AreaMoveService {

    /** Undo, as for every stock write (D8): the mover, for two minutes. */
    static final Duration UNDO_WINDOW = Duration.ofMinutes(2);

    private final NamedParameterJdbcTemplate jdbc;
    private final BranchStockService branchStockService;
    private final BranchAccessService branchAccessService;
    private final StockManagementService stockManagementService;
    private final StockWritePermissionGuard stockWritePermissionGuard;

    /** One area as a move needs it. */
    private record AreaRow(UUID id, String name, boolean keepsStock, boolean active) {
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
        UUID fromAreaId = branchStockService.resolveAreaForWrite(branchId, request.fromAreaId(), AreaUse.TAKE);
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
        jdbc.update("""
                INSERT INTO area_moves (id, client_id, branch_id, from_area_id, to_area_id, status, moved_by, moved_at,
                                        recorded_offline, note)
                VALUES (:id, :clientId, :branchId, :fromAreaId, :toAreaId, 'DONE', :userId, :movedAt, :late, :note)
                """, new MapSqlParameterSource()
                        .addValue("id", request.id())
                        .addValue("clientId", clientId)
                        .addValue("branchId", branchId)
                        .addValue("fromAreaId", fromAreaId)
                        .addValue("toAreaId", to.id())
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
            StockManagementService.AreaMovePiece piece = stockManagementService.moveBetweenAreas(
                    line.productId(), branchId, fromAreaId, to.id(), to.keepsStock(), line.quantity(),
                    blankToNull(line.unit()), movedAt, late, usedNote, userId);
            jdbc.update("""
                    INSERT INTO area_move_lines (move_id, product_id, quantity, entered_unit, entered_quantity, used_movement_id)
                    VALUES (:moveId, :productId, :quantity, :enteredUnit, :enteredQuantity, :usedMovementId)
                    """, new MapSqlParameterSource()
                            .addValue("moveId", request.id())
                            .addValue("productId", line.productId())
                            .addValue("quantity", piece.quantity())
                            .addValue("enteredUnit", piece.enteredUnit())
                            .addValue("enteredQuantity", piece.enteredQuantity())
                            .addValue("usedMovementId", piece.usedMovementId()));
        }
        log.info("Area move {}: {} lines, area {} -> {} at branch {} by {}", request.id(), lines.size(), fromAreaId,
                to.id(), branchId, userId);
        return get(request.id(), userId);
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
        OffsetDateTime createdAt = (OffsetDateTime) move.get("created_at");
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

    private List<AreaMoveResponse> read(String where, MapSqlParameterSource params, UUID userId, int offset, int limit) {
        params.addValue("offset", offset).addValue("limit", limit);
        List<Map<String, Object>> moves = jdbc.queryForList("""
                SELECT m.id, m.branch_id, m.from_area_id, fa.name AS from_name, m.to_area_id, ta.name AS to_name,
                       ta.keeps_stock AS to_keeps_stock, m.status, m.moved_by, m.moved_at, m.note, m.created_at,
                       m.recorded_offline,
                       NULLIF(TRIM(COALESCE(u.first_name, '') || ' ' || COALESCE(u.last_name, '')), '') AS full_name,
                       u.username
                FROM area_moves m
                JOIN areas fa ON fa.id = m.from_area_id
                JOIN areas ta ON ta.id = m.to_area_id
                LEFT JOIN users u ON u.id = m.moved_by
                WHERE """ + where + """
                 ORDER BY m.moved_at DESC, m.created_at DESC
                OFFSET :offset LIMIT :limit
                """, params);
        if (moves.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<AreaMoveResponse.Line>> linesByMove = new LinkedHashMap<>();
        jdbc.query("""
                SELECT l.move_id, l.product_id, p.name, l.quantity, l.entered_quantity, l.entered_unit
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
                                    false));
                });
        List<AreaMoveResponse> out = new ArrayList<>();
        for (Map<String, Object> m : moves) {
            UUID id = (UUID) m.get("id");
            boolean toKeepsStock = Boolean.TRUE.equals(m.get("to_keeps_stock"));
            OffsetDateTime createdAt = (OffsetDateTime) m.get("created_at");
            boolean undoable = "DONE".equals(m.get("status")) && userId != null && userId.equals(m.get("moved_by"))
                    && !Boolean.TRUE.equals(m.get("recorded_offline"))
                    && createdAt.isAfter(OffsetDateTime.now().minus(UNDO_WINDOW));
            String name = m.get("full_name") != null ? (String) m.get("full_name") : (String) m.get("username");
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
                    (OffsetDateTime) m.get("moved_at"),
                    (String) m.get("note"),
                    undoable ? createdAt.plus(UNDO_WINDOW) : null,
                    // A give's lines were Used there (D-E7): said by the area it went to.
                    linesByMove.getOrDefault(id, List.of()).stream()
                            .map(line -> new AreaMoveResponse.Line(line.productId(), line.productName(), line.quantity(),
                                    line.enteredQuantity(), line.enteredUnit(), !toKeepsStock))
                            .toList()));
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
                SELECT id, branch_id, from_area_id, to_area_id, status, moved_by, created_at, recorded_offline
                FROM area_moves WHERE id = :id AND client_id = :clientId
                """, new MapSqlParameterSource().addValue("id", moveId).addValue("clientId", clientId));
        if (found.isEmpty()) {
            throw new AreaMoveNotFoundException();
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
