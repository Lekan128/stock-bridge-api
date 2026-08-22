package com.procurepal_services.stock_bridge_api.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The append-only guard on the money-history tables, and its maintenance escape hatch, in
 * all four combinations that exist: UPDATE and DELETE, with the hatch open and shut.
 *
 * <h2>Why this needed a class of its own</h2>
 * The guard was covered from one side only. {@code VendorSettlementIntegrationTest} proved
 * the refusals it cared about and used the hatch for its own cleanup, which is a DELETE -
 * and a DELETE is the one operation the hatch always handled correctly. Nothing anywhere
 * exercised an UPDATE THROUGH the hatch, so nothing noticed that V14's
 * {@code RETURN COALESCE(OLD, NEW)} returns OLD in a BEFORE UPDATE trigger and therefore
 * writes the row back unchanged. The statement succeeds. It reports its rows affected. It
 * does nothing.
 *
 * <p>That is not a hypothetical: V15 had to backfill {@code matures_at}, reached for the
 * documented hatch, and would have shipped a migration that passed on a developer machine
 * (no ledger rows, nothing to fail on), left the column NULL everywhere with real history,
 * and then died on {@code SET NOT NULL} in production. V15 spotted it and routed around it
 * with a warning in its header. V16 fixes the function; this class is the coverage that
 * would have caught it, written so the same gap cannot reopen.
 *
 * <h2>The four cases, and which one is the point</h2>
 * <ul>
 *   <li>UPDATE, hatch shut - refused. The rule.</li>
 *   <li>DELETE, hatch shut - refused. The rule.</li>
 *   <li>DELETE, hatch open - deletes. Worked before V16; asserted so it stays working,
 *       because the whole test suite's cleanup depends on it.</li>
 *   <li>UPDATE, hatch open - <b>changes the row</b>. The V16 fix. Note what is asserted:
 *       not that the statement succeeded, which it always did, but that the stored value
 *       afterwards is the NEW one. An assertion on the return of the UPDATE would have
 *       passed against the broken function.</li>
 * </ul>
 *
 * <h2>Everything runs on one connection</h2>
 * {@code app.ledger_maintenance} is session state and a {@code JdbcTemplate} call takes
 * whichever connection the pool hands it, so a bare {@code SET} followed by a separate
 * statement would very likely land on two different sessions and prove nothing. Both
 * helpers below therefore take a {@link ConnectionCallback}. The shut-hatch helper
 * additionally RESETs before it runs: the pool is shared with the rest of the suite, and a
 * test that inherited a connection somebody left the flag on would assert a refusal that
 * silently never had to happen.
 *
 * <h2>Fixtures are a PAYOUT entry, which is the cheap shape</h2>
 * {@code chk_vendor_ledger_entries_subject_shape} makes every non-PAYOUT entry name an
 * order AND an order item, so planting one means planting a buyer, an address, a product
 * and an order first. A PAYOUT entry names a batch instead, and a batch is one INSERT. The
 * trigger does not read any of it - it fires FOR EACH ROW on the table - so the cheap shape
 * is also the honest one here.
 *
 * <p>Runs against the local docker-compose Postgres like every other integration test here.
 */
@SpringBootTest
@ActiveProfiles("local")
class LedgerAppendOnlyTriggerIntegrationTest {

    /** Names every row this class creates, so @AfterEach removes exactly those. */
    private static final String FIXTURE_PREFIX = "PP-TRIG-";

    @Autowired
    private JdbcTemplate jdbc;

    private UUID sellerId;
    private UUID entryId;

    @BeforeEach
    void setUp() {
        cleanFixtures();
        sellerId = plantVendor();
        entryId = plantPayoutEntry(sellerId, plantBatch(sellerId));
    }

    @AfterEach
    void tearDown() {
        cleanFixtures();
    }

    // ---------------------------------------------------------------------------------
    // Hatch shut: the rule. This is what the trigger is for and V16 must not weaken it.
    // ---------------------------------------------------------------------------------

    @Test
    void anUpdateIsRefusedWhenTheMaintenanceFlagIsNotSet() {
        assertThatThrownBy(() -> withHatchShut(statement ->
                        statement.execute("UPDATE vendor_ledger_entries SET memo = 'rewritten' WHERE id = '"
                                + entryId + "'")))
                .hasMessageContaining("append-only")
                .hasMessageContaining("reverses_entry_id");

        assertThat(memoOf(entryId)).isEqualTo("Original memo");
    }

    @Test
    void aDeleteIsRefusedWhenTheMaintenanceFlagIsNotSet() {
        assertThatThrownBy(() -> withHatchShut(statement ->
                        statement.execute("DELETE FROM vendor_ledger_entries WHERE id = '" + entryId + "'")))
                .hasMessageContaining("append-only");

        assertThat(rowExists(entryId)).isTrue();
    }

    /**
     * The refusal names the operation it refused, which is the difference between an error a
     * reader can act on and one they have to reproduce. Asserted because V16 rewrote the
     * function body around this RAISE and it would have been easy to lose the {@code TG_OP}
     * argument while moving it.
     */
    @Test
    void theRefusalNamesTheOperationItRefused() {
        assertThatThrownBy(() -> withHatchShut(statement ->
                        statement.execute("UPDATE vendor_ledger_entries SET memo = 'x' WHERE id = '" + entryId + "'")))
                .hasMessageContaining("UPDATE");

        assertThatThrownBy(() -> withHatchShut(statement ->
                        statement.execute("DELETE FROM vendor_ledger_entries WHERE id = '" + entryId + "'")))
                .hasMessageContaining("DELETE");
    }

    /**
     * The second table V15 attached to the same function. One escape hatch was supposed to
     * cover every money-history table in the schema; this is the assertion that it still
     * does, from the refusing side.
     */
    @Test
    void theSettlementSettingsHistoryIsGuardedByTheSameFunction() {
        UUID changeId = plantSettingsChange();

        assertThatThrownBy(() -> withHatchShut(statement ->
                        statement.execute("UPDATE vendor_settlement_settings_changes SET new_hold_days = 30 "
                                + "WHERE id = '" + changeId + "'")))
                .hasMessageContaining("append-only");

        assertThat(jdbc.queryForObject(
                        "SELECT new_hold_days FROM vendor_settlement_settings_changes WHERE id = ?",
                        Integer.class,
                        changeId))
                .isEqualTo(14);
    }

    // ---------------------------------------------------------------------------------
    // Hatch open: the escape, which must actually escape.
    // ---------------------------------------------------------------------------------

    /**
     * <b>The V16 fix.</b> Before it, this statement succeeded and reported one row affected
     * while leaving the memo exactly as it was - so the assertion that matters is on the
     * STORED VALUE, not on the statement. A test that checked only "it did not throw" passes
     * against the broken function, which is precisely how the bug survived two migrations.
     */
    @Test
    void anUpdateActuallyChangesTheRowWhenTheMaintenanceFlagIsSet() {
        withHatchOpen(statement -> statement.execute(
                "UPDATE vendor_ledger_entries SET memo = 'corrected by hand' WHERE id = '" + entryId + "'"));

        assertThat(memoOf(entryId))
                .as("the hatch is supposed to permit an UPDATE, not to swallow it")
                .isEqualTo("corrected by hand");
    }

    /** The same fix on the other table sharing the function, since one CREATE OR REPLACE fixed both. */
    @Test
    void anUpdateToTheSettlementSettingsHistoryAlsoLandsWhenTheFlagIsSet() {
        UUID changeId = plantSettingsChange();

        withHatchOpen(statement -> statement.execute(
                "UPDATE vendor_settlement_settings_changes SET reason = 'amended' WHERE id = '" + changeId + "'"));

        assertThat(jdbc.queryForObject(
                        "SELECT reason FROM vendor_settlement_settings_changes WHERE id = ?", String.class, changeId))
                .isEqualTo("amended");
    }

    /**
     * DELETE was always correct - {@code COALESCE(OLD, NEW)} returns OLD, which is what a
     * DELETE needs - and this asserts V16 did not break it while fixing its neighbour. It is
     * not an academic worry: every settlement test's cleanup runs through this path, so
     * losing it would leave one run's fixtures in the next run's balances.
     */
    @Test
    void aDeleteStillRemovesTheRowWhenTheMaintenanceFlagIsSet() {
        withHatchOpen(statement ->
                statement.execute("DELETE FROM vendor_ledger_entries WHERE id = '" + entryId + "'"));

        assertThat(rowExists(entryId)).isFalse();
    }

    /**
     * The hatch permits the write; it does not exempt it from the table's own rules. A BEFORE
     * trigger returning NEW hands the row on to constraint evaluation, so an UPDATE that
     * would produce a nonsensical row still fails - loudly, which is the opposite of the
     * failure mode V16 removed.
     *
     * <p>Worth pinning because "the guard is off" reads like "anything goes", and the person
     * most likely to read it that way is somebody writing a data fix against the money
     * tables at an unsociable hour.
     */
    @Test
    void theHatchDoesNotSuspendTheTablesCheckConstraints() {
        assertThatThrownBy(() -> withHatchOpen(statement -> statement.execute(
                        "UPDATE vendor_ledger_entries SET amount = 1.00 WHERE id = '" + entryId + "'")))
                .hasMessageContaining("chk_vendor_ledger_entries_sign");

        assertThat(jdbc.queryForObject(
                        "SELECT amount FROM vendor_ledger_entries WHERE id = ?", BigDecimal.class, entryId))
                .isEqualByComparingTo("-500.00");
    }

    /**
     * The flag is a session setting, so leaving it on would quietly disarm the guard for
     * every later user of that pooled connection. Both helpers reset it; this proves the
     * open one does, by running a refusal on the very next statement of the SAME session.
     */
    @Test
    void theHatchClosesAgainOnTheSameConnection() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET app.ledger_maintenance = 'on'");
                statement.execute("UPDATE vendor_ledger_entries SET memo = 'first' WHERE id = '" + entryId + "'");
                statement.execute("RESET app.ledger_maintenance");

                assertThatThrownBy(() -> statement.execute(
                                "UPDATE vendor_ledger_entries SET memo = 'second' WHERE id = '" + entryId + "'"))
                        .hasMessageContaining("append-only");
            }
            return null;
        });

        assertThat(memoOf(entryId)).isEqualTo("first");
    }

    // ---------------------------------------------------------------------------------
    // Running statements with the hatch open, and with it shut
    // ---------------------------------------------------------------------------------

    /** Anything that can be run against one open JDBC {@link Statement}. */
    @FunctionalInterface
    private interface StatementWork {
        void run(Statement statement) throws SQLException;
    }

    /**
     * Runs {@code work} with {@code app.ledger_maintenance = 'on'}, on one connection, and
     * resets it afterwards even if the work throws - the pool is shared with the rest of the
     * suite and a connection left with the guard disarmed is worse than a failing test.
     */
    private void withHatchOpen(StatementWork work) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET app.ledger_maintenance = 'on'");
                try {
                    work.run(statement);
                } finally {
                    statement.execute("RESET app.ledger_maintenance");
                }
            }
            return null;
        });
    }

    /**
     * Runs {@code work} with the flag explicitly cleared first. The RESET is not
     * belt-and-braces: without it this asserts a refusal on whatever connection the pool
     * happened to hand over, and if some other test left the flag on, the refusal would
     * silently never be tested at all.
     */
    private void withHatchShut(StatementWork work) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("RESET app.ledger_maintenance");
                work.run(statement);
            }
            return null;
        });
    }

    // ---------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------

    private UUID plantVendor() {
        UUID clientId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        jdbc.update(
                "INSERT INTO clients (id, name, slug, admin_contact_email, client_type, is_active) "
                        + "VALUES (?, ?, ?, ?, 'VENDOR', TRUE)",
                clientId,
                FIXTURE_PREFIX + suffix,
                (FIXTURE_PREFIX + suffix).toLowerCase(),
                "trigger-" + suffix + "@example.com");
        return clientId;
    }

    private UUID plantBatch(UUID seller) {
        UUID batchId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO vendor_payout_batches (id, seller_client_id, batch_number, period_start, period_end, "
                        + "status, proceeds_total, commission_total, reversal_total, net_amount, line_count) "
                        + "VALUES (?, ?, ?, ?, ?, 'PENDING', 500.00, 0.00, 0.00, 500.00, 1)",
                batchId,
                seller,
                FIXTURE_PREFIX + UUID.randomUUID().toString().substring(0, 8),
                OffsetDateTime.parse("2019-01-01T00:00:00Z"),
                OffsetDateTime.parse("2019-01-15T00:00:00Z"));
        return batchId;
    }

    /**
     * A PAYOUT entry: negative amount, a batch rather than an order, and no reversal or
     * commission columns - the one entry shape that satisfies every CHECK on the table
     * without a whole order behind it.
     */
    private UUID plantPayoutEntry(UUID seller, UUID batchId) {
        UUID id = UUID.randomUUID();
        OffsetDateTime when = OffsetDateTime.parse("2019-01-16T00:00:00Z");
        jdbc.update(
                "INSERT INTO vendor_ledger_entries (id, seller_client_id, entry_type, amount, currency, "
                        + "payout_batch_id, occurred_at, matures_at, memo, idempotency_key) "
                        + "VALUES (?, ?, 'PAYOUT', -500.00, 'NGN', ?, ?, ?, 'Original memo', ?)",
                id,
                seller,
                batchId,
                when,
                when,
                FIXTURE_PREFIX + UUID.randomUUID());
        return id;
    }

    /** A row on the OTHER table the same trigger function guards. */
    private UUID plantSettingsChange() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO vendor_settlement_settings_changes (id, previous_hold_days, new_hold_days, "
                        + "changed_by_username, reason) VALUES (?, 7, 14, ?, ?)",
                id,
                FIXTURE_PREFIX + "operator",
                FIXTURE_PREFIX + "reason");
        return id;
    }

    /**
     * Ledger entries first (the batch they name is ON DELETE RESTRICT), and through the hatch
     * - which is the whole reason the hatch exists, and, now that UPDATE works too, no longer
     * the only thing it can do.
     */
    private void cleanFixtures() {
        withHatchOpen(statement -> {
            statement.execute(
                    "DELETE FROM vendor_ledger_entries WHERE idempotency_key LIKE '" + FIXTURE_PREFIX + "%'");
            statement.execute("DELETE FROM vendor_settlement_settings_changes WHERE changed_by_username LIKE '"
                    + FIXTURE_PREFIX + "%'");
        });
        jdbc.update("DELETE FROM vendor_payout_batches WHERE batch_number LIKE ?", FIXTURE_PREFIX + "%");
        jdbc.update("DELETE FROM clients WHERE slug LIKE ?", FIXTURE_PREFIX.toLowerCase() + "%");
    }

    // ---------------------------------------------------------------------------------
    // Assertions
    // ---------------------------------------------------------------------------------

    private String memoOf(UUID id) {
        return jdbc.queryForObject("SELECT memo FROM vendor_ledger_entries WHERE id = ?", String.class, id);
    }

    private boolean rowExists(UUID id) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM vendor_ledger_entries WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }
}
