package com.procurepal_services.stock_bridge_api.selling;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * How a sale was paid (SELLING_TILL_PLAN.md Step 1), kept on the {@code sales} row (V88).
 *
 * <p>The row is written with a sale's FIRST line to arrive, from the payment that line carries, so an
 * offline sale sends nothing extra. Every line of one basket carries the same payment; a later line
 * finds the row already there and leaves it. Writing it is repeat-safe by construction
 * ({@code ON CONFLICT DO NOTHING}), which also means a sale id that belongs to another company can never
 * be touched from here.
 */
@Service
@RequiredArgsConstructor
public class SalePaymentService {

    private final NamedParameterJdbcTemplate jdbc;

    /** Records how a sale was paid, unless its row already exists. */
    @Transactional
    public void recordIfAbsent(UUID clientId, UUID saleId, UUID branchId, SalePaymentMethod method, UUID userId) {
        if (saleId == null || method == null) {
            return;
        }
        jdbc.update(
                """
                INSERT INTO sales (id, client_id, branch_id, payment_method, recorded_by)
                VALUES (:id, :clientId, :branchId, :method, :userId)
                ON CONFLICT (id) DO NOTHING
                """,
                new MapSqlParameterSource()
                        .addValue("id", saleId)
                        .addValue("clientId", clientId)
                        .addValue("branchId", branchId)
                        .addValue("method", method.name())
                        .addValue("userId", userId));
    }

    /** How this company's sale was paid; empty for a sale from before payments were recorded. */
    @Transactional(readOnly = true)
    public Optional<SalePaymentMethod> methodOf(UUID clientId, UUID saleId) {
        List<String> found = jdbc.queryForList(
                "SELECT payment_method FROM sales WHERE id = :id AND client_id = :clientId",
                new MapSqlParameterSource().addValue("id", saleId).addValue("clientId", clientId),
                String.class);
        return found.isEmpty() ? Optional.empty() : Optional.of(SalePaymentMethod.valueOf(found.get(0)));
    }

    /**
     * Sets how a sale was paid - "Change how they paid", or a payment given to a sale that had none.
     * Who may do it is the caller's rule ({@link SaleService#changePayment}).
     */
    @Transactional
    public void set(UUID clientId, UUID saleId, UUID branchId, SalePaymentMethod method, UUID userId) {
        int changed = jdbc.update(
                """
                UPDATE sales SET payment_method = :method, payment_changed_by = :userId, payment_changed_at = :now
                WHERE id = :id AND client_id = :clientId AND payment_method <> :method
                """,
                new MapSqlParameterSource()
                        .addValue("id", saleId)
                        .addValue("clientId", clientId)
                        .addValue("method", method.name())
                        .addValue("userId", userId)
                        .addValue("now", OffsetDateTime.now()));
        if (changed == 0) {
            recordIfAbsent(clientId, saleId, branchId, method, userId);
        }
    }
}
