package com.procurepal_services.stock_bridge_api.branch;

import com.procurepal_services.stock_bridge_api.product.unit.Quantities;
import com.procurepal_services.stock_bridge_api.imports.ImportCopy;
import com.procurepal_services.stock_bridge_api.branch.dto.AreaMoveRequest;
import com.procurepal_services.stock_bridge_api.branch.dto.AreaMoveResponse;
import com.procurepal_services.stock_bridge_api.branch.dto.AreaReturnRequest;
import com.procurepal_services.stock_bridge_api.entity.NotificationType;
import com.procurepal_services.stock_bridge_api.entity.OutReason;
import com.procurepal_services.stock_bridge_api.notification.NotificationService;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOptions;
import com.procurepal_services.stock_bridge_api.settlement.PayoutCadence;
import com.procurepal_services.stock_bridge_api.stock.StockManagementService;
import com.procurepal_services.stock_bridge_api.stock.StockWritePermissionGuard;
import com.procurepal_services.stock_bridge_api.stock.dto.StockOutRequest;
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
 * Giving back and reporting what an area received (STOCK_AREAS_PART_6.md, STOCK_AREAS_PLAN.md §5.6, D-A6; owner,
 * 2026-11-01: "the kitchen might accept the can and want to give it back on inspection that it has expired").
 * Picked from a move of the last {@link AreaReturns#WINDOW}; never more than arrived of the line, less what's come
 * back or been reported already.
 * <ul>
 *   <li>From an area that keeps stock (the Kitchen): a give back is a move back to where it came from, linked to
 *       this one, saying why; a report is a stock-out there with its reason.</li>
 *   <li>From an area that uses stock up (Housekeeping), or a line used straight away: a give back is a partial
 *       reversal of the Used stock-out ({@link StockManagementService#giveBack}); a report changes no figure.</li>
 * </ul>
 * Either way the area it came from and the owner hear about it, once each.
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
                SELECT m.id, m.branch_id, m.from_area_id, m.to_area_id, m.status, m.moved_at, m.moved_by, m.waits,
                       fa.name AS from_name, ta.name AS to_name, ta.keeps_stock AS to_keeps_stock
                FROM area_moves m JOIN areas fa ON fa.id = m.from_area_id JOIN areas ta ON ta.id = m.to_area_id
                WHERE m.id = :id AND m.client_id = :clientId
                """, new MapSqlParameterSource().addValue("id", moveId).addValue("clientId", clientId));
        if (found.isEmpty()) {
            throw new AreaMoveNotFoundException();
        }
        Map<String, Object> move = found.getFirst();
        String toName = (String) move.get("to_name");
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
        // What arrived of it (Part 4: what was confirmed, when it waited).
        BigDecimal used = Quantities.of(line.get("quantity_confirmed") != null ? line.get("quantity_confirmed")
                : line.get("quantity"));
        BigDecimal already = jdbc.queryForObject("""
                SELECT COALESCE(SUM(quantity), 0) FROM area_move_returns WHERE move_id = :id AND product_id = :productId
                """, new MapSqlParameterSource().addValue("id", moveId).addValue("productId", request.productId()),
                BigDecimal.class);
        BigDecimal quantity = stockManagementService.inStockUnits(request.productId(), request.quantity(),
                blankToNull(request.unit())).quantity();
        String unit = (String) line.get("unit_of_measure");
        String product = (String) line.get("name");
        BigDecimal left = used.subtract(already);
        if (quantity.compareTo(left) > 0) {
            throw new BranchChangeRefusedException(left.signum() <= 0
                    ? "All of the " + product + " given has been given back or reported already."
                    : "Only " + amount(left, unit) + " of the " + product + " is left to give back or report.");
        }

        String note = blankToNull(request.note());
        boolean late = Boolean.TRUE.equals(request.recordedOffline());
        UUID usedMovementId = (UUID) line.get("used_movement_id");
        // Used up there (Housekeeping, or a line used straight away): nothing in the area's figure to take it from.
        boolean usedUp = !Boolean.TRUE.equals(move.get("to_keeps_stock")) || usedMovementId != null;
        String why = request.reason() == null ? null : reasonWord(request.reason());
        UUID movementId = null;
        UUID backMoveId = null;
        if (request.kind() == AreaReturnRequest.Kind.GIVE_BACK && usedUp) {
            if (usedMovementId == null) {
                throw new BranchChangeRefusedException("This give can't be taken back here. Move it back instead.");
            }
            movementId = stockManagementService.giveBack(usedMovementId, quantity,
                    "Given back by the " + toName + (why != null ? ", " + why : "") + (note != null ? ": " + note : ""),
                    at, late, userId).getId();
        } else if (request.kind() == AreaReturnRequest.Kind.GIVE_BACK) {
            // A move back to where it came from, its own id the give back's (a resend is one), linked to this move.
            backMoveId = request.id();
            areaMoveService.move(new AreaMoveRequest(backMoveId, branchId, toAreaId, (UUID) move.get("from_area_id"), at,
                    late, "Given back" + (why != null ? ", " + why : "") + (note != null ? ": " + note : ""),
                    List.of(new AreaMoveRequest.Line(request.productId(), quantity, null))), userId);
            jdbc.update("UPDATE area_moves SET gives_back_move_id = :moveId WHERE id = :id",
                    new MapSqlParameterSource().addValue("moveId", moveId).addValue("id", backMoveId));
        } else if (!usedUp) {
            // Reported from an area that keeps stock: written off there, for its reason.
            movementId = stockManagementService.stockOut(request.productId(), new StockOutRequest(quantity, null,
                    capitalise(why) + " in the " + toName + (note != null ? ": " + note : ""), null, null, at, late,
                    outReason(request.reason()), null, null, branchId, toAreaId), userId).movement().id();
        }
        jdbc.update("""
                INSERT INTO area_move_returns (id, client_id, branch_id, move_id, product_id, kind, quantity, reason, note,
                                               movement_id, back_move_id, created_by, occurred_at)
                VALUES (:id, :clientId, :branchId, :moveId, :productId, :kind, :quantity, :reason, :note, :movementId,
                        :backMoveId, :userId, :at)
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
                        .addValue("backMoveId", backMoveId)
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
    private void tell(UUID clientId, Map<String, Object> move, AreaReturnRequest request, BigDecimal quantity, String unit,
            String product, String note, UUID userId) {
        UUID branchId = (UUID) move.get("branch_id");
        Set<UUID> people = new LinkedHashSet<>(areaPeople.of((UUID) move.get("from_area_id"), branchId, userId));
        people.addAll(jdbc.queryForList("SELECT id FROM users WHERE client_id = :clientId AND is_root AND is_active",
                new MapSqlParameterSource("clientId", clientId), UUID.class));
        people.remove(userId);
        String toName = (String) move.get("to_name");
        String what = amount(quantity, unit) + " of " + product;
        String title = request.kind() == AreaReturnRequest.Kind.GIVE_BACK
                ? "The " + toName + " gave back " + what + (request.reason() != null ? ": " + reasonWord(request.reason()) : "")
                : "The " + toName + " reported " + what + " " + reasonWord(request.reason());
        String mover = areaPeople.name((UUID) move.get("moved_by"));
        String body = "Moved " + DAY.format(timestamp(move.get("moved_at"))) + (mover != null ? " by " + mover : "")
                + (note != null ? " · " + note : "");
        for (UUID person : people) {
            notificationService.notifyClient(clientId, person, NotificationType.AREA_RETURN, title, body,
                    "/app?move=" + move.get("id"), null);
        }
    }

    /** The stock-out a report from an area that keeps stock is written off as: spoilt is damaged. */
    private static OutReason outReason(AreaReturnRequest.Reason reason) {
        return switch (reason) {
            case SPOILT, DAMAGED -> OutReason.DAMAGED;
            case EXPIRED -> OutReason.EXPIRED;
            case OTHER -> OutReason.OTHER;
        };
    }

    private static String capitalise(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
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
    private static String amount(BigDecimal quantity, String unit) {
        String word = UnitOptions.sizeUnitWord(unit, quantity);
        // A product with no stock unit is counted in plain units, as the sheets call it.
        return ImportCopy.count(quantity) + " "
                + (word == null || word.isEmpty() ? (quantity.compareTo(BigDecimal.ONE) == 0 ? "unit" : "units") : word);
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
