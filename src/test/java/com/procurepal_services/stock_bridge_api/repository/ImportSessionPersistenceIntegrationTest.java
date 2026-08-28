package com.procurepal_services.stock_bridge_api.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.entity.ImportKind;
import com.procurepal_services.stock_bridge_api.entity.ImportMode;
import com.procurepal_services.stock_bridge_api.entity.ImportRowStatus;
import com.procurepal_services.stock_bridge_api.entity.ImportSession;
import com.procurepal_services.stock_bridge_api.entity.ImportSessionRow;
import com.procurepal_services.stock_bridge_api.entity.ImportStatus;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The V20 import escrow at the persistence layer - the two entities, their {@code jsonb}
 * columns, and every repository query the session engine (module M4) is going to build on.
 *
 * <h2>Why this exists as a separate class from the ledger tests</h2>
 * {@code import_sessions} and {@code import_session_rows} have no HTTP surface yet - the
 * controller and service that will drive them belong to a later module. Left untested until that
 * module arrives, the first thing to discover a wrong {@code jsonb} mapping or a mis-typed
 * native query would be the module that has to trust them, at which point the failure looks
 * like a bug in the session engine rather than in its foundation. {@code ddl-auto: validate}
 * already proves the column names and types line up on every boot; what it cannot prove is that
 * a {@code Map<String, Object>} survives a round trip through Postgres with its structure
 * intact, or that {@code raw ->> :column} groups the way its javadoc claims. Those are runtime
 * facts, and this is where they get asserted.
 *
 * <h2>Repositories directly, not through HTTP</h2>
 * Unlike its sibling integration tests, this one calls the repositories. That is deliberate: the
 * subject IS the repository contract. It therefore has to set {@link TenantContext} by hand -
 * normally {@code TenantResolutionFilter} does that from an authenticated principal - and it
 * clears it in an {@code @AfterEach}, because the context is thread-local and the surefire
 * thread is shared with the rest of the suite. A leaked tenant id would not fail here; it would
 * fail somewhere else, later, for no visible reason.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class ImportSessionPersistenceIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ImportSessionRepository sessionRepository;

    @Autowired
    private ImportSessionRowRepository rowRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /**
     * The {@code jsonb} round trip, which is the one thing {@code ddl-auto: validate} cannot
     * check. Nested structure matters as much as the values: {@code value_mappings} is keyed by
     * column and then by the raw value that appeared in the file (design doc 6.4), so a mapping
     * that flattened or stringified one level would still store and still read back - it would
     * just quietly stop being able to answer "which vendor did they say 'Dangote Ltd' was".
     */
    @Test
    void jsonColumnsRoundTripWithTheirStructureIntact() {
        UUID clientId = signupTenant("Json Roundtrip Co");
        TenantContext.set(clientId);

        ImportSession session = newSession(ImportStatus.NEEDS_REVIEW, OffsetDateTime.now().plusHours(48));
        session.setColumnMapping(Map.of("Product Name", "name", "SKU", "sku"));
        session.setValueMappings(
                Map.of("vendor_name", Map.of("Dangote Ltd", Map.of("kind", "EXISTING", "id", "abc-123"))));
        ImportSession saved = sessionRepository.save(session);

        ImportSessionRow row = rowRepository.save(ImportSessionRow.builder()
                .session(saved)
                .excelRow(4)
                .status(ImportRowStatus.ERROR)
                .raw(Map.of("sku", "GARRI-25", "unit_of_measure", "KGS"))
                .normalized(Map.of("sku", "GARRI-25"))
                .errors(List.of(Map.of(
                        "column", "unit_of_measure",
                        "message", "We don't recognise \"KGS\" as a unit.",
                        "bulkFixCount", 12)))
                .build());

        ImportSession reloaded = sessionRepository.findByIdForCurrentTenant(saved.getId()).orElseThrow();
        assertThat(reloaded.getColumnMapping()).containsEntry("Product Name", "name");
        assertThat(reloaded.getValueMappings()).containsKey("vendor_name");
        // The nesting survives - two levels down, still a map, still keyed by the file's own value.
        assertThat(reloaded.getValueMappings().get("vendor_name"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsKey("Dangote Ltd");

        ImportSessionRow reloadedRow = rowRepository.findByIdAndSessionId(row.getId(), saved.getId()).orElseThrow();
        assertThat(reloadedRow.getRaw()).containsEntry("unit_of_measure", "KGS");
        assertThat(reloadedRow.getNormalized()).doesNotContainKey("unit_of_measure");
        assertThat(reloadedRow.getErrors()).hasSize(1);
        // A number stays a number rather than becoming the string "12" - bulkFixCount is what
        // renders [Fix all 12 "KGS" rows], and the frontend is promised it never has to compute
        // or coerce it (contract section 4).
        assertThat(reloadedRow.getErrors().get(0)).containsEntry("bulkFixCount", 12);
        assertThat(reloadedRow.isCommittable()).isFalse();
    }

    /**
     * The GROUP BY that lets the session's five cached counters be recomputed without loading a
     * row - see {@code ImportSessionRowRepository.countByStatus} for why loading them instead
     * would quietly make the review screen unusable at the row cap it advertises.
     */
    @Test
    void countByStatusGroupsWithoutLoadingRows() {
        UUID clientId = signupTenant("Counters Co");
        TenantContext.set(clientId);
        ImportSession session = sessionRepository.save(newSession(ImportStatus.NEEDS_REVIEW, OffsetDateTime.now().plusHours(48)));

        saveRow(session, 2, ImportRowStatus.VALID, Map.of("vendor_name", "Dangote Ltd"));
        saveRow(session, 3, ImportRowStatus.VALID, Map.of("vendor_name", "Dangote Ltd"));
        saveRow(session, 4, ImportRowStatus.ERROR, Map.of("vendor_name", "Dangote Ltd"));
        saveRow(session, 5, ImportRowStatus.SKIPPED, Map.of("vendor_name", "Ade & Sons"));

        Map<ImportRowStatus, Long> counts = new EnumMap<>(ImportRowStatus.class);
        for (Object[] pair : rowRepository.countByStatus(session.getId())) {
            counts.put((ImportRowStatus) pair[0], (Long) pair[1]);
        }

        assertThat(counts)
                .containsEntry(ImportRowStatus.VALID, 2L)
                .containsEntry(ImportRowStatus.ERROR, 1L)
                .containsEntry(ImportRowStatus.SKIPPED, 1L)
                .doesNotContainKey(ImportRowStatus.COMMITTED);

        // The distinct-value collapse of design doc 6.4, reaching inside the raw jsonb: one
        // decision fixes 3 rows, not 3 decisions fixing 1 row each.
        List<Object[]> distinct = rowRepository.countDistinctRawValues(session.getId(), "vendor_name");
        assertThat(distinct).hasSize(2);
        assertThat(distinct.get(0)[0]).isEqualTo("Dangote Ltd");
        assertThat(((Number) distinct.get(0)[1]).longValue()).isEqualTo(3L);

        // Paging the grid by status reads straight off idx_import_session_rows_session_id_status.
        assertThat(rowRepository
                        .findAllBySessionIdAndStatusOrderByExcelRowAsc(
                                session.getId(), ImportRowStatus.VALID, PageRequest.of(0, 50))
                        .getContent())
                .extracting(ImportSessionRow::getExcelRow)
                .containsExactly(2, 3);
    }

    /**
     * The expiry job's read, and the line it must not cross. A COMMITTED session is deliberately
     * kept past its TTL - it is the undo record and the target of every {@code import_batch_id} -
     * so it must never appear here. That is not merely a preference: both {@code
     * stock_movements.import_batch_id} and {@code products.import_batch_id} are {@code ON DELETE
     * RESTRICT}, so a job that collected one would not silently do damage, it would fail.
     */
    @Test
    void expiredUncommittedSessionsAreCollectableAndCommittedOnesNeverAre() {
        UUID clientId = signupTenant("Expiry Co");
        TenantContext.set(clientId);

        OffsetDateTime longAgo = OffsetDateTime.now().minusHours(72);
        ImportSession abandoned = sessionRepository.save(newSession(ImportStatus.NEEDS_REVIEW, longAgo));
        ImportSession committedSession = newSession(ImportStatus.COMMITTED, longAgo);
        committedSession.setCommittedAt(OffsetDateTime.now().minusHours(71));
        ImportSession committed = sessionRepository.save(committedSession);
        ImportSession stillFresh = sessionRepository.save(newSession(ImportStatus.READY, OffsetDateTime.now().plusHours(48)));

        List<UUID> collectable = sessionRepository
                .findExpiredUncommitted(
                        OffsetDateTime.now(),
                        List.of(ImportStatus.PARSING, ImportStatus.NEEDS_REVIEW, ImportStatus.READY,
                                ImportStatus.FAILED, ImportStatus.EXPIRED),
                        PageRequest.of(0, 100))
                .map(ImportSession::getId)
                .getContent();

        assertThat(collectable).contains(abandoned.getId());
        assertThat(collectable).doesNotContain(committed.getId(), stillFresh.getId());
        assertThat(abandoned.isUncommitted()).isTrue();
        assertThat(committed.isUncommitted()).isFalse();
    }

    /**
     * The idempotency guard of design doc section 11, at the level this module owns: the lock
     * exists, and it is tenant-scoped. That the lock actually serializes two commits is the
     * session engine's test to write once there is a commit to serialize; what has to be true
     * first is that a caller from another company cannot acquire it. A lock granted before the
     * tenancy check would let a stranger block somebody else's commit without ever reading a row.
     */
    @Test
    void theCommitLockIsScopedToTheOwningTenant() {
        UUID owner = signupTenant("Lock Owner Co");
        UUID stranger = signupTenant("Lock Stranger Co");
        TenantContext.set(owner);
        ImportSession session = sessionRepository.save(newSession(ImportStatus.READY, OffsetDateTime.now().plusHours(48)));

        transactionTemplate.executeWithoutResult(status -> {
            assertThat(sessionRepository.findByIdAndClientIdForUpdate(session.getId(), owner)).isPresent();
            assertThat(sessionRepository.findByIdAndClientIdForUpdate(session.getId(), stranger)).isEmpty();
        });

        // And the same isolation on the ordinary read path the review screen uses.
        TenantContext.set(stranger);
        assertThat(sessionRepository.findByIdForCurrentTenant(session.getId())).isEmpty();
    }

    // -----------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------

    private ImportSession newSession(ImportStatus status, OffsetDateTime expiresAt) {
        return ImportSession.builder()
                .kind(ImportKind.PRODUCT_CATALOG)
                .mode(ImportMode.CREATE_ONLY)
                .status(status)
                .originalFilename("products-jan.xlsx")
                .expiresAt(expiresAt)
                .build();
    }

    private void saveRow(ImportSession session, int excelRow, ImportRowStatus status, Map<String, Object> raw) {
        rowRepository.save(ImportSessionRow.builder()
                .session(session)
                .excelRow(excelRow)
                .status(status)
                .raw(raw)
                .normalized(raw)
                .build());
    }

    /** Signs a tenant up over HTTP so the clients/users rows really exist, then resolves its id. */
    private UUID signupTenant(String name) {
        String unique = UUID.randomUUID().toString();
        ClientSignupRequest request = new ClientSignupRequest(
                name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD);
        TenantLoginResponse response = restTemplate.postForObject("/api/clients/signup", request, TenantLoginResponse.class);
        return jdbc.queryForObject(
                "SELECT client_id FROM users WHERE id = ?", UUID.class, response.user().id());
    }
}
