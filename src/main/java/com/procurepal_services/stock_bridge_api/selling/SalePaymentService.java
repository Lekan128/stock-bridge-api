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
        recordIfAbsent(clientId, saleId, branchId, method, null, userId);
    }

    /** ...and, for a transfer, which of the branch's accounts it came into (V96, Step 12). */
    @Transactional
    public void recordIfAbsent(UUID clientId, UUID saleId, UUID branchId, SalePaymentMethod method, UUID accountId, UUID userId) {
        if (saleId == null || method == null) {
            return;
        }
        jdbc.update(
                """
                INSERT INTO sales (id, client_id, branch_id, payment_method, payment_account_id, recorded_by)
                VALUES (:id, :clientId, :branchId, :method, :accountId, :userId)
                ON CONFLICT (id) DO NOTHING
                """,
                new MapSqlParameterSource()
                        .addValue("id", saleId)
                        .addValue("clientId", clientId)
                        .addValue("branchId", branchId)
                        .addValue("method", method.name())
                        .addValue("accountId", method == SalePaymentMethod.TRANSFER ? accountId : null)
                        .addValue("userId", userId));
    }

    /** How this company's sale was paid; empty for a sale from before payments were recorded. */
    @Transactional(readOnly = true)
    public Optional<SalePaymentMethod> methodOf(UUID clientId, UUID saleId) {
        List<String> found = jdbc.queryForList(
                "SELECT payment_method FROM sales WHERE id = :id AND client_id = :clientId",
                new MapSqlParameterSource().addValue("id", saleId).addValue("clientId", clientId),
                String.class);
        return found.isEmpty() || found.get(0) == null ? Optional.empty() : Optional.of(SalePaymentMethod.valueOf(found.get(0)));
    }

    /**
     * Sets how a sale was paid - "Change how they paid", or a payment given to a sale that had none.
     * Who may do it is the caller's rule ({@link SaleService#changePayment}).
     */
    @Transactional
    public void set(UUID clientId, UUID saleId, UUID branchId, SalePaymentMethod method, UUID userId) {
        set(clientId, saleId, branchId, method, null, userId);
    }

    /** ...with the account a transfer came into (V96); a change to another account alone is a change too. */
    @Transactional
    public void set(UUID clientId, UUID saleId, UUID branchId, SalePaymentMethod method, UUID accountId, UUID userId) {
        UUID account = method == SalePaymentMethod.TRANSFER ? accountId : null;
        int changed = jdbc.update(
                """
                UPDATE sales SET payment_method = :method, payment_account_id = :accountId,
                                 payment_changed_by = :userId, payment_changed_at = :now
                WHERE id = :id AND client_id = :clientId
                  AND (payment_method IS DISTINCT FROM :method OR payment_account_id IS DISTINCT FROM CAST(:accountId AS uuid))
                """,
                new MapSqlParameterSource()
                        .addValue("id", saleId)
                        .addValue("clientId", clientId)
                        .addValue("method", method.name())
                        .addValue("accountId", account)
                        .addValue("userId", userId)
                        .addValue("now", OffsetDateTime.now()));
        if (changed == 0) {
            recordIfAbsent(clientId, saleId, branchId, method, account, userId);
        }
    }

    // ------------------------------------------------------------------ cancelling (Step 3) ----

    /** A sale's own row as it stands: how it was paid, and where cancelling it has got to. Names already resolved. */
    public record Header(
            SalePaymentMethod paymentMethod,
            String cancelState,
            SaleCancelReason cancelReason,
            String cancelNote,
            UUID cancelRequestedBy,
            String cancelRequestedByName,
            OffsetDateTime cancelRequestedAt,
            String cancelDecidedByName,
            OffsetDateTime cancelDecidedAt,
            UUID paymentAccountId,
            String paymentAccountName) {
        static final Header NONE = new Header(null, null, null, null, null, null, null, null, null, null, null);
    }

    @Transactional(readOnly = true)
    public Header header(UUID clientId, UUID saleId) {
        List<Header> found = jdbc.query(
                """
                SELECT s.payment_method, s.cancel_state, s.cancel_reason, s.cancel_note, s.cancel_requested_by,
                       COALESCE(NULLIF(TRIM(CONCAT(r.first_name, ' ', r.last_name)), ''), r.username) AS requested_name,
                       s.cancel_requested_at,
                       COALESCE(NULLIF(TRIM(CONCAT(d.first_name, ' ', d.last_name)), ''), d.username) AS decided_name,
                       s.cancel_decided_at, s.payment_account_id, pa.bank_name AS account_name
                FROM sales s
                LEFT JOIN payment_accounts pa ON pa.id = s.payment_account_id
                LEFT JOIN users r ON r.id = s.cancel_requested_by
                LEFT JOIN users d ON d.id = s.cancel_decided_by
                WHERE s.id = :id AND s.client_id = :clientId
                """,
                new MapSqlParameterSource().addValue("id", saleId).addValue("clientId", clientId),
                (rs, n) -> new Header(
                        rs.getString("payment_method") == null ? null : SalePaymentMethod.valueOf(rs.getString("payment_method")),
                        rs.getString("cancel_state"),
                        rs.getString("cancel_reason") == null ? null : SaleCancelReason.valueOf(rs.getString("cancel_reason")),
                        rs.getString("cancel_note"),
                        rs.getObject("cancel_requested_by", UUID.class),
                        rs.getString("requested_name"),
                        rs.getObject("cancel_requested_at", OffsetDateTime.class),
                        rs.getString("decided_name"),
                        rs.getObject("cancel_decided_at", OffsetDateTime.class),
                        rs.getObject("payment_account_id", UUID.class),
                        rs.getString("account_name")));
        return found.isEmpty() ? Header.NONE : found.get(0);
    }

    /** The whole sale was cancelled: why, by whom, when. A reason already given (on the request) is kept. */
    @Transactional
    public void markCancelled(UUID clientId, UUID saleId, UUID branchId, SaleCancelReason reason, String note, UUID by) {
        jdbc.update(
                """
                INSERT INTO sales (id, client_id, branch_id, cancel_state, cancel_reason, cancel_note, cancel_decided_by,
                                   cancel_decided_at)
                VALUES (:id, :clientId, :branchId, 'CANCELLED', :reason, :note, :by, :now)
                ON CONFLICT (id) DO UPDATE SET
                    cancel_state = 'CANCELLED',
                    cancel_reason = COALESCE(EXCLUDED.cancel_reason, sales.cancel_reason),
                    cancel_note = COALESCE(EXCLUDED.cancel_note, sales.cancel_note),
                    cancel_decided_by = EXCLUDED.cancel_decided_by,
                    cancel_decided_at = EXCLUDED.cancel_decided_at
                WHERE sales.client_id = EXCLUDED.client_id
                """,
                cancelParams(clientId, saleId, branchId, reason, note, by));
    }

    /** A cashier asks to cancel: waiting for someone who can cancel sales. Asking again replaces the reason. */
    @Transactional
    public void markRequested(UUID clientId, UUID saleId, UUID branchId, SaleCancelReason reason, String note, UUID by) {
        jdbc.update(
                """
                INSERT INTO sales (id, client_id, branch_id, cancel_state, cancel_reason, cancel_note, cancel_requested_by,
                                   cancel_requested_at)
                VALUES (:id, :clientId, :branchId, 'REQUESTED', :reason, :note, :by, :now)
                ON CONFLICT (id) DO UPDATE SET
                    cancel_state = 'REQUESTED',
                    cancel_reason = EXCLUDED.cancel_reason,
                    cancel_note = EXCLUDED.cancel_note,
                    cancel_requested_by = EXCLUDED.cancel_requested_by,
                    cancel_requested_at = EXCLUDED.cancel_requested_at,
                    cancel_decided_by = NULL,
                    cancel_decided_at = NULL
                WHERE sales.client_id = EXCLUDED.client_id
                """,
                cancelParams(clientId, saleId, branchId, reason, note, by));
    }

    /** The request was declined: the sale stands. */
    @Transactional
    public void markDeclined(UUID clientId, UUID saleId, UUID by) {
        jdbc.update(
                """
                UPDATE sales SET cancel_state = 'DECLINED', cancel_decided_by = :by, cancel_decided_at = :now
                WHERE id = :id AND client_id = :clientId AND cancel_state = 'REQUESTED'
                """,
                new MapSqlParameterSource()
                        .addValue("id", saleId)
                        .addValue("clientId", clientId)
                        .addValue("by", by)
                        .addValue("now", OffsetDateTime.now()));
    }

    private static MapSqlParameterSource cancelParams(
            UUID clientId, UUID saleId, UUID branchId, SaleCancelReason reason, String note, UUID by) {
        return new MapSqlParameterSource()
                .addValue("id", saleId)
                .addValue("clientId", clientId)
                .addValue("branchId", branchId)
                .addValue("reason", reason == null ? null : reason.name())
                .addValue("note", note == null || note.isBlank() ? null : note.trim())
                .addValue("by", by)
                .addValue("now", OffsetDateTime.now());
    }
}
