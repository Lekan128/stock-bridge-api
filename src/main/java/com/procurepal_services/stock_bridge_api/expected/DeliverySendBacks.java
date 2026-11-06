package com.procurepal_services.stock_bridge_api.expected;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Goods sent back at the gate (V79, PURCHASE_ORDER_PLAN.md section 11.6): written beside the delivery
 * they came on, read back only for deliveries that went in (committed), so a delivery that was
 * discarded or undone never says anything was sent back.
 */
@Component
@RequiredArgsConstructor
public class DeliverySendBacks {

    /** One line sent back: what, how many in which unit, and why. */
    public record SendBack(UUID productId, String unit, BigDecimal quantity, String reason, String note) {
    }

    /** As the order's history reads it. */
    public record Recorded(UUID importSessionId, UUID productId, String productName, String unit,
            BigDecimal quantity, String reason, String note) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public void record(UUID clientId, UUID importSessionId, UUID expectedDeliveryId, List<SendBack> sendBacks) {
        for (SendBack back : sendBacks) {
            jdbc.update("""
                    INSERT INTO delivery_send_backs
                        (id, client_id, import_session_id, expected_delivery_id, product_id, unit, quantity, reason, note, created_at)
                    VALUES (:id, :clientId, :sessionId, :orderId, :productId, :unit, :quantity, :reason, :note, :at)
                    """, new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("clientId", clientId)
                    .addValue("sessionId", importSessionId)
                    .addValue("orderId", expectedDeliveryId)
                    .addValue("productId", back.productId())
                    .addValue("unit", back.unit())
                    .addValue("quantity", back.quantity())
                    .addValue("reason", back.reason())
                    .addValue("note", back.note())
                    .addValue("at", Timestamp.from(OffsetDateTime.now().toInstant())));
        }
    }

    /** What was sent back on these committed deliveries, by delivery, in the order recorded. */
    public Map<UUID, List<Recorded>> ofCommitted(UUID clientId, Collection<UUID> importSessionIds) {
        Map<UUID, List<Recorded>> byDelivery = new HashMap<>();
        if (importSessionIds.isEmpty()) {
            return byDelivery;
        }
        jdbc.query("""
                SELECT b.import_session_id, b.product_id, p.name, b.unit, b.quantity, b.reason, b.note
                FROM delivery_send_backs b
                JOIN import_sessions s ON s.id = b.import_session_id AND s.status = 'COMMITTED'
                JOIN products p ON p.id = b.product_id
                WHERE b.client_id = :clientId AND b.import_session_id IN (:ids)
                ORDER BY b.created_at
                """, new MapSqlParameterSource().addValue("clientId", clientId).addValue("ids", importSessionIds),
                row -> {
                    UUID session = row.getObject(1, UUID.class);
                    byDelivery.computeIfAbsent(session, key -> new ArrayList<>()).add(new Recorded(
                            session, row.getObject(2, UUID.class), row.getString(3), row.getString(4),
                            row.getBigDecimal(5), row.getString(6), row.getString(7)));
                });
        return byDelivery;
    }
}
