package com.procurepal_services.stock_bridge_api.selling;

import com.procurepal_services.stock_bridge_api.branch.BranchNotFoundException;
import com.procurepal_services.stock_bridge_api.product.MadeWithException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A branch's charges and taxes (V100, selling till Step 10; owner, 2026-11-19). One list per branch, VAT there to start
 * with (off). Each is a % of the food and drinks or a fixed amount per sale, included in the prices or added on top, on
 * every sale or on table bills only - each worked out on the food and drinks, never on another charge, to the naira.
 * A sale keeps its charges as they stood when it was made ({@code sale_charges}); their amounts follow its lines.
 */
@Service
@RequiredArgsConstructor
public class ChargeService {

    private final NamedParameterJdbcTemplate jdbc;

    public record Charge(
            UUID id, UUID branchId, String name, String kind, BigDecimal value, boolean included, String applies,
            boolean active, int sortOrder) {}

    public record ChargeRequest(String name, String kind, BigDecimal value, Boolean included, String applies, Boolean active) {}

    /** One charge on one sale: as it was set, and what it came to. */
    public record SaleCharge(UUID chargeId, String name, String kind, BigDecimal value, boolean included, BigDecimal amount) {}

    // ------------------------------------------------------------------------------------------- the list

    @Transactional(readOnly = true)
    public Map<UUID, List<Charge>> byBranch(UUID clientId, boolean withOff) {
        Map<UUID, List<Charge>> result = new LinkedHashMap<>();
        jdbc.query("SELECT id, branch_id, name, kind, value, included, applies, is_active, sort_order FROM branch_charges"
                        + " WHERE client_id = :clientId" + (withOff ? "" : " AND is_active") + " ORDER BY sort_order, created_at",
                new MapSqlParameterSource("clientId", clientId),
                rs -> {
                    Charge charge = new Charge(rs.getObject("id", UUID.class), rs.getObject("branch_id", UUID.class),
                            rs.getString("name"), rs.getString("kind"), rs.getBigDecimal("value"), rs.getBoolean("included"),
                            rs.getString("applies"), rs.getBoolean("is_active"), rs.getInt("sort_order"));
                    result.computeIfAbsent(charge.branchId(), key -> new ArrayList<>()).add(charge);
                });
        return result;
    }

    /** The branch's list for whoever manages it - with VAT 7.5% (off) put there the first time it's looked at. */
    @Transactional
    public List<Charge> list(UUID clientId, UUID branchId) {
        requireBranch(clientId, branchId);
        jdbc.update("""
                INSERT INTO branch_charges (client_id, branch_id, name, kind, value, included, applies, is_active)
                SELECT :clientId, :branchId, 'VAT', 'PERCENT', 7.5, FALSE, 'EVERY_SALE', FALSE
                WHERE NOT EXISTS (SELECT 1 FROM branch_charges WHERE branch_id = :branchId)
                """, new MapSqlParameterSource().addValue("clientId", clientId).addValue("branchId", branchId));
        return byBranch(clientId, true).getOrDefault(branchId, List.of());
    }

    @Transactional
    public List<Charge> add(UUID clientId, UUID branchId, ChargeRequest request) {
        List<Charge> existing = list(clientId, branchId);
        String name = name(request.name(), existing, null);
        Checked checked = check(request.kind(), request.value(), request.applies());
        jdbc.update("""
                INSERT INTO branch_charges (client_id, branch_id, name, kind, value, included, applies, is_active, sort_order)
                VALUES (:clientId, :branchId, :name, :kind, :value, :included, :applies, :active, :order)
                """, new MapSqlParameterSource().addValue("clientId", clientId).addValue("branchId", branchId)
                .addValue("name", name).addValue("kind", checked.kind()).addValue("value", checked.value())
                .addValue("included", Boolean.TRUE.equals(request.included())).addValue("applies", checked.applies())
                .addValue("active", request.active() == null || request.active()).addValue("order", existing.size()));
        return byBranch(clientId, true).getOrDefault(branchId, List.of());
    }

    @Transactional
    public List<Charge> update(UUID clientId, UUID chargeId, ChargeRequest request) {
        Map<String, Object> row = jdbc.queryForList("""
                SELECT branch_id, name, kind, value, included, applies, is_active FROM branch_charges
                WHERE id = :id AND client_id = :clientId
                """, new MapSqlParameterSource().addValue("id", chargeId).addValue("clientId", clientId))
                .stream().findFirst().orElseThrow(() -> new MadeWithException("No such charge."));
        UUID branchId = (UUID) row.get("branch_id");
        List<Charge> existing = byBranch(clientId, true).getOrDefault(branchId, List.of());
        String name = request.name() == null ? (String) row.get("name") : name(request.name(), existing, chargeId);
        Checked checked = check(request.kind() == null ? (String) row.get("kind") : request.kind(),
                request.value() == null ? (BigDecimal) row.get("value") : request.value(),
                request.applies() == null ? (String) row.get("applies") : request.applies());
        jdbc.update("""
                UPDATE branch_charges SET name = :name, kind = :kind, value = :value, included = :included,
                                          applies = :applies, is_active = :active
                WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", chargeId).addValue("name", name)
                .addValue("kind", checked.kind()).addValue("value", checked.value())
                .addValue("included", request.included() == null ? row.get("included") : request.included())
                .addValue("applies", checked.applies())
                .addValue("active", request.active() == null ? row.get("is_active") : request.active()));
        return byBranch(clientId, true).getOrDefault(branchId, List.of());
    }

    // ------------------------------------------------------------------------------------------- the arithmetic

    /**
     * What each charge comes to on {@code subtotal} of food and drinks. Added on top: a % of it, or the fixed amount.
     * Included: taken out of the prices together - the fixed ones first, then the %s on what's left, so 7.5% VAT in a
     * ₦1,075 price is ₦75. Each rounded to the naira; nothing on a sale with nothing on it.
     */
    public static List<BigDecimal> amounts(BigDecimal subtotal, List<SaleCharge> charges) {
        List<BigDecimal> amounts = new ArrayList<>();
        if (subtotal == null || subtotal.signum() <= 0) {
            charges.forEach(charge -> amounts.add(BigDecimal.ZERO));
            return amounts;
        }
        BigDecimal fixedIn = BigDecimal.ZERO;
        BigDecimal rateIn = BigDecimal.ZERO;
        for (SaleCharge charge : charges) {
            if (charge.included()) {
                if ("AMOUNT".equals(charge.kind())) fixedIn = fixedIn.add(charge.value());
                else rateIn = rateIn.add(charge.value());
            }
        }
        BigDecimal base = subtotal.subtract(fixedIn).max(BigDecimal.ZERO)
                .divide(BigDecimal.ONE.add(rateIn.movePointLeft(2)), 6, RoundingMode.HALF_UP);
        for (SaleCharge charge : charges) {
            BigDecimal amount;
            if ("AMOUNT".equals(charge.kind())) {
                amount = charge.value();
            } else {
                amount = (charge.included() ? base : subtotal).multiply(charge.value()).movePointLeft(2);
            }
            amounts.add(amount.setScale(0, RoundingMode.HALF_UP).setScale(2, RoundingMode.UNNECESSARY));
        }
        return amounts;
    }

    /** What a sale with {@code subtotal} of food and drinks comes to: it plus every charge added on top. */
    public static BigDecimal toPay(BigDecimal subtotal, List<SaleCharge> charges) {
        List<BigDecimal> amounts = amounts(subtotal, charges);
        BigDecimal total = subtotal == null ? BigDecimal.ZERO : subtotal;
        for (int i = 0; i < charges.size(); i++) {
            if (!charges.get(i).included()) total = total.add(amounts.get(i));
        }
        return total;
    }

    /** The charges a sale at this branch would carry now: every sale's, and table bills' too when it's a bill. */
    @Transactional(readOnly = true)
    public List<SaleCharge> current(UUID clientId, UUID branchId, boolean bill) {
        return byBranch(clientId, false).getOrDefault(branchId, List.of()).stream()
                .filter(charge -> bill || "EVERY_SALE".equals(charge.applies()))
                .map(charge -> new SaleCharge(charge.id(), charge.name(), charge.kind(), charge.value(), charge.included(), BigDecimal.ZERO))
                .toList();
    }

    // ------------------------------------------------------------------------------------------- on sales

    /**
     * A counter sale's line was recorded: its first line fixes the sale's charges as they stand now; every line brings
     * the amounts up to date. A table's bill takes its charges when it's paid ({@link #fix}).
     */
    @Transactional
    public void afterSaleLine(UUID clientId, UUID saleId, UUID branchId, UUID lineId) {
        if (saleId == null || branchId == null) {
            return;
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM bills WHERE id = :id)",
                new MapSqlParameterSource("id", saleId), Boolean.class))) {
            return;
        }
        boolean first = !Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM stock_movements WHERE sale_id = :saleId AND id <> :lineId AND movement_type = 'OUT')
                """, new MapSqlParameterSource().addValue("saleId", saleId).addValue("lineId", lineId), Boolean.class));
        if (first) {
            fix(clientId, saleId, branchId, false);
        } else {
            refresh(clientId, saleId);
        }
    }

    /** Fixes a sale's charges as they stand now - a counter sale's at its first line, a bill's when it's paid. */
    @Transactional
    public void fix(UUID clientId, UUID saleId, UUID branchId, boolean bill) {
        List<SaleCharge> charges = current(clientId, branchId, bill);
        int position = 0;
        for (SaleCharge charge : charges) {
            jdbc.update("""
                    INSERT INTO sale_charges (sale_id, charge_id, client_id, name, kind, value, included, position)
                    VALUES (:saleId, :chargeId, :clientId, :name, :kind, :value, :included, :position)
                    ON CONFLICT (sale_id, charge_id) DO NOTHING
                    """, new MapSqlParameterSource().addValue("saleId", saleId).addValue("chargeId", charge.chargeId())
                    .addValue("clientId", clientId).addValue("name", charge.name()).addValue("kind", charge.kind())
                    .addValue("value", charge.value()).addValue("included", charge.included()).addValue("position", position++));
        }
        refresh(clientId, saleId);
    }

    /** Works a sale's charges out again on its lines as they stand - after a line is cancelled or priced. */
    @Transactional
    public void refresh(UUID clientId, UUID saleId) {
        List<SaleCharge> charges = of(clientId, saleId);
        if (charges.isEmpty()) {
            return;
        }
        List<BigDecimal> amounts = amounts(subtotal(clientId, saleId), charges);
        for (int i = 0; i < charges.size(); i++) {
            jdbc.update("UPDATE sale_charges SET amount = :amount WHERE sale_id = :saleId AND charge_id = :chargeId",
                    new MapSqlParameterSource().addValue("amount", amounts.get(i)).addValue("saleId", saleId)
                            .addValue("chargeId", charges.get(i).chargeId()));
        }
    }

    @Transactional(readOnly = true)
    public List<SaleCharge> of(UUID clientId, UUID saleId) {
        return jdbc.query("""
                SELECT charge_id, name, kind, value, included, amount FROM sale_charges
                WHERE sale_id = :saleId AND client_id = :clientId ORDER BY position
                """, new MapSqlParameterSource().addValue("saleId", saleId).addValue("clientId", clientId),
                (rs, n) -> new SaleCharge(rs.getObject("charge_id", UUID.class), rs.getString("name"), rs.getString("kind"),
                        rs.getBigDecimal("value"), rs.getBoolean("included"), rs.getBigDecimal("amount")));
    }

    /** The sale's food and drinks: its lines not cancelled, not taken off a bill, at what they went for. */
    @Transactional(readOnly = true)
    public BigDecimal subtotal(UUID clientId, UUID saleId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(COALESCE(o.sale_amount, c.sale_amount, 0)), 0)
                FROM stock_movements o
                LEFT JOIN stock_movements r ON r.reverses_movement_id = o.id
                LEFT JOIN stock_movements c ON c.corrects_movement_id = o.id
                LEFT JOIN bill_lines bl ON bl.movement_id = o.id
                WHERE o.sale_id = :saleId AND o.client_id = :clientId AND o.movement_type = 'OUT'
                  AND r.id IS NULL AND bl.taken_off IS NULL
                """, new MapSqlParameterSource().addValue("saleId", saleId).addValue("clientId", clientId), BigDecimal.class);
    }

    // ------------------------------------------------------------------------------------------- checks

    private record Checked(String kind, BigDecimal value, String applies) {}

    private static Checked check(String kind, BigDecimal value, String applies) {
        if (!"PERCENT".equals(kind) && !"AMOUNT".equals(kind)) {
            throw new MadeWithException("Choose % or ₦.");
        }
        if (value == null || value.signum() <= 0) {
            throw new MadeWithException("Say how much.");
        }
        if ("PERCENT".equals(kind) && value.compareTo(BigDecimal.valueOf(100)) >= 0) {
            throw new MadeWithException("A charge is less than 100%.");
        }
        String when = applies == null ? "EVERY_SALE" : applies;
        if (!"EVERY_SALE".equals(when) && !"TABLE_BILLS".equals(when)) {
            throw new MadeWithException("Choose every sale or table bills only.");
        }
        return new Checked(kind, value.setScale(2, RoundingMode.HALF_UP), when);
    }

    private static String name(String typed, List<Charge> existing, UUID except) {
        String name = typed == null ? "" : typed.trim().replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            throw new MadeWithException("Give it the name the receipt should show.");
        }
        if (name.length() > 40) {
            throw new MadeWithException("Keep the name to 40 letters.");
        }
        if (existing.stream().anyMatch(charge -> !charge.id().equals(except) && charge.name().equalsIgnoreCase(name))) {
            throw new MadeWithException("There's already a charge called " + name + " here.");
        }
        return name;
    }

    private void requireBranch(UUID clientId, UUID branchId) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM branches WHERE id = :id AND client_id = :clientId)",
                new MapSqlParameterSource().addValue("id", branchId).addValue("clientId", clientId), Boolean.class))) {
            throw new BranchNotFoundException();
        }
    }
}
