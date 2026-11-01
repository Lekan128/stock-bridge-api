package com.procurepal_services.stock_bridge_api.branch;

import com.procurepal_services.stock_bridge_api.branch.dto.AreaMoveResponse;
import com.procurepal_services.stock_bridge_api.branch.dto.AreaReturnRequest;
import com.procurepal_services.stock_bridge_api.entity.NotificationType;
import com.procurepal_services.stock_bridge_api.notification.NotificationService;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOptions;
import com.procurepal_services.stock_bridge_api.settlement.PayoutCadence;
import com.procurepal_services.stock_bridge_api.stock.StockManagementService;
import com.procurepal_services.stock_bridge_api.stock.StockWritePermissionGuard;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Giving back and reporting from an area that uses stock up as soon as it arrives (STOCK_AREAS_PART_6.md,
 * STOCK_AREAS_PLAN.md §5.6, D-A6): Housekeeping got 3 bags of rice and used 2 - it gives 1 back to the Store, or
 * reports tomatoes spoilt. Picked from a give of the last {@link AreaReturns#WINDOW}; never more than was used of
 * the line, less what's come back or been reported already. A give back is a partial reversal of the Used
 * stock-out at the Store ({@link StockManagementService#giveBack}); a report changes no figure. Either way the
 * area it came from and the owner hear about it, once each.
 *
 * <p>Who may (D-E9, from the database on every call): someone who works in that area and can record stock in or out.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class AreaReturnService {

    /** "Monday" in a notification: the day where the business is. */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH).withZone(PayoutCadence.ZONE);

    private final NamedParameterJdbcTemplate jdbc;
    private final AreaReturns areaReturns;
    private final AreaMoveService areaMoveService;
    private final AreaAccessService areaAccessService;
    private final BranchAccessService branchAccessService;
    private final StockManagementService stockManagementService;
    private final StockWritePermissionGuard stockWritePermissionGuard;
    private final NotificationService notificationService;
    private final AreaPeople areaPeople;

    public AreaMoveResponse record(UUID moveId, AreaReturnRequest request, UUID userId) {
        UUID clientId = requireTenantId();
        // Sent again after a lost reply, or late from a phone that was offline: the first one stands.
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM area_move_returns WHERE id = :id AND client_id = :clientId)",
                new MapSqlParameterSource().addValue("id", request.id()).addValue("clientId", clientId), Boolean.class))) {
            return areaMoveService.get(moveId, userId);
        }
        List<Map<String, Object>> found = jdbc.queryForList("""
                SELECT m.id, m.branch_id, m.from_area_id, m.to_area_id, m.status, m.moved_at, m.moved_by,
                       fa.name AS from_name, ta.name AS to_name, ta.keeps_stock AS to_keeps_stock
                FROM area_moves m JOIN areas fa ON fa.id = m.from_area_id JOIN areas ta ON ta.id = m.to_area_id
                WHERE m.id = :id AND m.client_id = :clientId
                """, new MapSqlParameterSource().addValue("id", moveId).addValue("clientId", clientId));
        if (found.isEmpty()) {
            throw new AreaMoveNotFoundException();
        }
        Map<String, Object> move = found.getFirst();
        String toName = (String) move.get("to_name");
        if (Boolean.TRUE.equals(move.get("to_keeps_stock"))) {
            throw new BranchChangeRefusedException("The " + toName + " keeps its stock: record what's spoilt there as a "
                    + "stock-out, or move it back.");
        }
        String status = (String) move.get("status");
        if (!"DONE".equals(status) && !"CONFIRMED_SHORT".equals(status)) {
            throw new BranchChangeRefusedException("WAITING".equals(status)
                    ? "Confirm what arrived first."
                    : "This was taken back, so there's nothing to give back.");
        }
        OffsetDateTime at = request.occurredAt() != null ? request.occurredAt() : OffsetDateTime.now();
        if (timestamp(move.get("moved_at")).isBefore(at.minus(AreaReturns.WINDOW))) {
            throw new BranchChangeRefusedException("This was given more than 7 days ago, so it can't be given back or "
                    + "reported here. Record it as a stock-out at the " + move.get("from_name") + " instead.");
        }
        if (!areaReturns.mayRecord(userId)) {
            stockWritePermissionGuard.require(userId, "STOCK_OUT");
        }
        UUID branchId = (UUID) move.get("branch_id");
        UUID toAreaId = (UUID) move.get("to_area_id");
        branchAccessService.requireMayRecordAt(userId, branchId);
        areaAccessService.requireMayRecordIn(userId, branchId, toAreaId);
        if (request.kind() == AreaReturnRequest.Kind.REPORT && request.reason() == null) {
            throw new BranchChangeRefusedException("Say what happened to it: spoilt, expired, damaged or something else.");
        }

        // One at a time per line, so two phones can't both give back the last bag.
        List<Map<String, Object>> lines = jdbc.queryForList("""
                SELECT l.quantity, l.quantity_confirmed, l.used_movement_id, p.name, p.unit_of_measure
                FROM area_move_lines l JOIN products p ON p.id = l.product_id
                WHERE l.move_id = :id AND l.product_id = :productId
                FOR UPDATE OF l
                """, new MapSqlParameterSource().addValue("id", moveId).addValue("productId", request.productId()));
        if (lines.isEmpty()) {
            throw new BranchChangeRefusedException("That product isn't on this move.");
        }
        Map<String, Object> line = lines.getFirst();
        int used = line.get("quantity_confirmed") != null ? ((Number) line.get("quantity_confirmed")).intValue()
                : ((Number) line.get("quantity")).intValue();
        int already = jdbc.queryForObject("""
                SELECT COALESCE(SUM(quantity), 0) FROM area_move_returns WHERE move_id = :id AND product_id = :productId
                """, new MapSqlParameterSource().addValue("id", moveId).addValue("productId", request.productId()),
                Integer.class);
        int quantity = stockManagementService.inStockUnits(request.productId(), request.quantity(),
                blankToNull(request.unit())).quantity();
        String unit = (String) line.get("unit_of_measure");
        String product = (String) line.get("name");
        int left = used - already;
        if (quantity > left) {
            throw new BranchChangeRefusedException(left <= 0
                    ? "All of the " + product + " given has been given back or reported already."
                    : "Only " + amount(left, unit) + " of the " + product + " is left to give back or report.");
        }

        String note = blankToNull(request.note());
        UUID movementId = null;
        if (request.kind() == AreaReturnRequest.Kind.GIVE_BACK) {
            UUID usedMovementId = (UUID) line.get("used_movement_id");
            if (usedMovementId == null) {
                throw new BranchChangeRefusedException("This give can't be taken back here. Move it back instead.");
            }
            movementId = stockManagementService.giveBack(usedMovementId, quantity,
                    "Given back by the " + toName + (note != null ? ": " + note : ""), at,
                    Boolean.TRUE.equals(request.recordedOffline()), userId).getId();
        }
        jdbc.update("""
                INSERT INTO area_move_returns (id, client_id, branch_id, move_id, product_id, kind, quantity, reason, note,
                                               movement_id, created_by, occurred_at)
                VALUES (:id, :clientId, :branchId, :moveId, :productId, :kind, :quantity, :reason, :note, :movementId,
                        :userId, :at)
                """, new MapSqlParameterSource()
                        .addValue("id", request.id())
                        .addValue("clientId", clientId)
                        .addValue("branchId", branchId)
                        .addValue("moveId", moveId)
                        .addValue("productId", request.productId())
                        .addValue("kind", request.kind().name())
                        .addValue("quantity", quantity)
                        .addValue("reason", request.reason() == null ? null : request.reason().name())
                        .addValue("note", note)
                        .addValue("movementId", movementId)
                        .addValue("userId", userId)
                        .addValue("at", at));
        log.info("Area move {}: {} {} of product {} by {}", moveId, request.kind(), quantity, request.productId(), userId);

        tell(clientId, move, request, quantity, unit, product, note, userId);
        return areaMoveService.get(moveId, userId);
    }

    /**
     * "Housekeeping gave back 1 bag of Rice" / "Housekeeping reported 2 kg of Tomatoes spoilt" - "Moved Monday by
     * Amaka · the note", once each to the people of the area it came from (else those in every area) and the owners,
     * never to who did it.
     */
    private void tell(UUID clientId, Map<String, Object> move, AreaReturnRequest request, int quantity, String unit,
            String product, String note, UUID userId) {
        UUID branchId = (UUID) move.get("branch_id");
        Set<UUID> people = new LinkedHashSet<>(areaPeople.of((UUID) move.get("from_area_id"), branchId, userId));
        people.addAll(jdbc.queryForList("SELECT id FROM users WHERE client_id = :clientId AND is_root AND is_active",
                new MapSqlParameterSource("clientId", clientId), UUID.class));
        people.remove(userId);
        String toName = (String) move.get("to_name");
        String what = amount(quantity, unit) + " of " + product;
        String title = request.kind() == AreaReturnRequest.Kind.GIVE_BACK
                ? "The " + toName + " gave back " + what
                : "The " + toName + " reported " + what + " " + reasonWord(request.reason());
        String mover = areaPeople.name((UUID) move.get("moved_by"));
        String body = "Moved " + DAY.format(timestamp(move.get("moved_at"))) + (mover != null ? " by " + mover : "")
                + (note != null ? " · " + note : "");
        for (UUID person : people) {
            notificationService.notifyClient(clientId, person, NotificationType.AREA_RETURN, title, body,
                    "/app?move=" + move.get("id"), null);
        }
    }

    private static String reasonWord(AreaReturnRequest.Reason reason) {
        return switch (reason) {
            case SPOILT -> "spoilt";
            case EXPIRED -> "expired";
            case DAMAGED -> "damaged";
            case OTHER -> "not used";
        };
    }

    /** "1 bag", "2 kg", "3 pieces" - the stock unit as people say it. */
    private static String amount(int quantity, String unit) {
        String word = UnitOptions.sizeUnitWord(unit, BigDecimal.valueOf(quantity));
        // A product with no stock unit is counted in plain units, as the sheets call it.
        return quantity + " " + (word == null || word.isEmpty() ? (quantity == 1 ? "unit" : "units") : word);
    }

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
