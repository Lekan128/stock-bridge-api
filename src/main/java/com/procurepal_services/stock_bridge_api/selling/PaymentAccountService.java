package com.procurepal_services.stock_bridge_api.selling;

import com.procurepal_services.stock_bridge_api.branch.BranchNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The accounts a branch's customers pay into by transfer (V96, selling till Step 12). Set by whoever manages branches;
 * switched off, never deleted, so a sale keeps the account it came into.
 */
@Service
@RequiredArgsConstructor
public class PaymentAccountService {

    private final NamedParameterJdbcTemplate jdbc;

    public record PaymentAccount(UUID id, UUID branchId, String bankName, String accountName, String accountNumber, boolean active) {}

    public record AccountRequest(String bankName, String accountName, String accountNumber, Boolean active) {}

    /** Each branch's accounts: switched-on ones only, unless {@code withOff}. */
    @Transactional(readOnly = true)
    public Map<UUID, List<PaymentAccount>> byBranch(UUID clientId, boolean withOff) {
        Map<UUID, List<PaymentAccount>> result = new HashMap<>();
        jdbc.query("SELECT * FROM payment_accounts WHERE client_id = :clientId" + (withOff ? "" : " AND is_active")
                        + " ORDER BY is_active DESC, created_at",
                new MapSqlParameterSource("clientId", clientId),
                rs -> {
                    PaymentAccount account = map(rs);
                    result.computeIfAbsent(account.branchId(), id -> new ArrayList<>()).add(account);
                });
        return result;
    }

    @Transactional
    public PaymentAccount add(UUID clientId, UUID branchId, AccountRequest request) {
        Boolean theirs = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM branches WHERE id = :id AND client_id = :clientId)",
                new MapSqlParameterSource().addValue("id", branchId).addValue("clientId", clientId), Boolean.class);
        if (!Boolean.TRUE.equals(theirs)) {
            throw new BranchNotFoundException();
        }
        String bank = required(request.bankName(), "the bank");
        String number = required(request.accountNumber(), "the account number");
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO payment_accounts (id, client_id, branch_id, bank_name, account_name, account_number)
                    VALUES (:id, :clientId, :branchId, :bank, :name, :number)
                    """, new MapSqlParameterSource().addValue("id", id).addValue("clientId", clientId).addValue("branchId", branchId)
                    .addValue("bank", bank).addValue("name", blankToNull(request.accountName())).addValue("number", number));
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            throw new com.procurepal_services.stock_bridge_api.product.MadeWithException("That account is already listed here.");
        }
        return get(clientId, id);
    }

    @Transactional
    public PaymentAccount update(UUID clientId, UUID id, AccountRequest request) {
        PaymentAccount current = get(clientId, id);
        jdbc.update("""
                UPDATE payment_accounts SET bank_name = :bank, account_name = :name, account_number = :number, is_active = :active
                WHERE id = :id AND client_id = :clientId
                """, new MapSqlParameterSource().addValue("id", id).addValue("clientId", clientId)
                .addValue("bank", request.bankName() == null ? current.bankName() : required(request.bankName(), "the bank"))
                .addValue("name", request.accountName() == null ? current.accountName() : blankToNull(request.accountName()))
                .addValue("number", request.accountNumber() == null ? current.accountNumber() : required(request.accountNumber(), "the account number"))
                .addValue("active", request.active() == null ? current.active() : request.active()));
        return get(clientId, id);
    }

    /** A sale's account: one of this branch's switched-on accounts, else refused. */
    public void requireUsable(UUID clientId, UUID branchId, UUID accountId) {
        Boolean ok = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM payment_accounts WHERE id = :id AND client_id = :clientId
                               AND (CAST(:branchId AS uuid) IS NULL OR branch_id = :branchId) AND is_active)
                """, new MapSqlParameterSource().addValue("id", accountId).addValue("clientId", clientId).addValue("branchId", branchId),
                Boolean.class);
        if (!Boolean.TRUE.equals(ok)) {
            throw new com.procurepal_services.stock_bridge_api.product.MadeWithException("That account isn't one of this branch's. Choose another.");
        }
    }

    private PaymentAccount get(UUID clientId, UUID id) {
        return jdbc.query("SELECT * FROM payment_accounts WHERE id = :id AND client_id = :clientId",
                new MapSqlParameterSource().addValue("id", id).addValue("clientId", clientId), (rs, i) -> map(rs))
                .stream().findFirst().orElseThrow(() -> new com.procurepal_services.stock_bridge_api.product.MadeWithException("No such account."));
    }

    private static PaymentAccount map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PaymentAccount(rs.getObject("id", UUID.class), rs.getObject("branch_id", UUID.class), rs.getString("bank_name"),
                rs.getString("account_name"), rs.getString("account_number"), rs.getBoolean("is_active"));
    }

    private static String required(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new com.procurepal_services.stock_bridge_api.product.MadeWithException("Say " + what + ".");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
