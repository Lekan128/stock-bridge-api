package com.procurepal_services.stock_bridge_api.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.SuperAdminLoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.SuperAdminLoginResponse;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.email.EmailDispatcher;
import com.procurepal_services.stock_bridge_api.email.EmailKind;
import com.procurepal_services.stock_bridge_api.email.EmailMessage;
import com.procurepal_services.stock_bridge_api.entity.SuperAdmin;
import com.procurepal_services.stock_bridge_api.repository.SuperAdminRepository;
import com.procurepal_services.stock_bridge_api.settlement.dto.EscrowHoldSettingsResponse;
import java.sql.Statement;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * M9, part B: the escrow hold as a setting - who may change it, what they have to
 * prove, what gets written down, and who gets told.
 *
 * <h2>What this class is really testing</h2>
 * Not "the endpoint works". This setting decides when real money leaves the
 * business, so almost every test below asserts that something did NOT happen: that a
 * refused request wrote nothing, that a wrong password revealed nothing, that a
 * failing email un-did nothing. A near-miss implementation passes "a super admin can
 * change the hold" and fails most of what is here.
 *
 * <h2>Every test restores the hold in tearDown</h2>
 * The settings row is a SINGLETON shared by the whole Spring context and by every
 * other test class in the suite - {@code VendorSettlementIntegrationTest}'s accrual
 * arithmetic reads it. A test that left the hold at 60 would silently change what
 * "payable" means in a class it has never heard of, which is exactly the kind of
 * cross-test coupling that produces an order-dependent suite.
 *
 * <p>Runs against the local docker-compose Postgres like every other integration
 * test here - see AuthIntegrationTest for why local Postgres over Testcontainers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class EscrowHoldSettingsIntegrationTest {

    private static final String SETTINGS = "/api/superadmin/settlement/settings";
    private static final String ESCROW_HOLD = SETTINGS + "/escrow-hold";
    private static final String PASSWORD = "correct-horse-battery-staple";

    /** Distinct from the real password by more than a typo, so a substring bug cannot pass. */
    private static final String WRONG_PASSWORD = "not-the-password-at-all";

    private static final String ADMIN_PREFIX = "m9-superadmin-";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SuperAdminRepository superAdminRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private EscrowHoldPolicy escrowHoldPolicy;

    @MockitoSpyBean
    private EmailDispatcher emailDispatcher;

    private SuperAdmin actor;
    private String actorToken;
    private int originalHoldDays;

    @BeforeEach
    void setUp() {
        cleanFixtures();
        originalHoldDays = escrowHoldPolicy.holdDays();
        actor = createSuperAdmin();
        actorToken = loginAsSuperAdmin(actor.getUsername());
        // The spy is one bean for the whole class, so without this every test sees
        // the mail every earlier test sent.
        clearInvocations(emailDispatcher);
    }

    @AfterEach
    void tearDown() {
        // Restore the singleton before anything else - see the class doc. Written
        // straight through SQL rather than through the endpoint, because the endpoint
        // would write an audit row that the cleanup below then has to remove anyway.
        jdbc.update("UPDATE vendor_settlement_settings SET escrow_hold_days = ? WHERE singleton", originalHoldDays);
        cleanFixtures();
    }

    // ---------------------------------------------------------------------------------
    // (a) Reading the setting
    // ---------------------------------------------------------------------------------

    /**
     * The defaults V15 seeds, and the two facts the screen cannot be trusted to know
     * on its own: the bounds, and that a change is not retroactive.
     */
    @Test
    void theSettingDefaultsToSevenDaysAndCarriesItsOwnBoundsAndRetroactivityRule() {
        EscrowHoldSettingsResponse settings = readSettings();

        assertThat(settings.escrowHoldDays())
                .as("V15 seeds the number the owner asked for, from the column default")
                .isEqualTo(EscrowHoldPolicy.DEFAULT_HOLD_DAYS)
                .isEqualTo(7);
        assertThat(settings.minHoldDays()).isZero();
        assertThat(settings.maxHoldDays()).isEqualTo(90);
        assertThat(settings.payoutPeriodDays())
                .as("the cadence is carried so a screen can warn about a hold longer than a cycle")
                .isEqualTo(PayoutCadence.PERIOD_DAYS);
        assertThat(settings.appliesToFutureAccrualsOnly())
                .as("the first question an operator asks, answered in the payload rather than in a comment")
                .isTrue();
        assertThat(settings.updatedAt()).isNotNull();
        assertThat(settings.lastChange()).as("nothing has changed it yet").isNull();
        assertThat(settings.recentChanges()).isEmpty();
    }

    // ---------------------------------------------------------------------------------
    // (b) The three gates
    // ---------------------------------------------------------------------------------

    /** The happy path, and the audit row and email that must go with it. */
    @Test
    void aSuperAdminWithTheirPasswordAndAnAcknowledgementChangesTheHold() {
        ResponseEntity<EscrowHoldSettingsResponse> response = putTyped(body(14, PASSWORD, true, "Fraud review"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().escrowHoldDays()).isEqualTo(14);
        assertThat(escrowHoldPolicy.holdDays())
                .as("and the value the ACCRUAL path reads, not just the one the screen echoes back")
                .isEqualTo(14);

        assertThat(response.getBody().lastChange()).isNotNull();
        assertThat(response.getBody().lastChange().previousHoldDays()).isEqualTo(7);
        assertThat(response.getBody().lastChange().newHoldDays()).isEqualTo(14);
        assertThat(response.getBody().lastChange().changedByUsername()).isEqualTo(actor.getUsername());
        assertThat(response.getBody().lastChange().reason()).isEqualTo("Fraud review");
        assertThat(response.getBody().recentChanges()).hasSize(1);
    }

    /**
     * The gate a bearer token cannot supply. A signed-in super admin with the WRONG
     * password changes nothing, and the refusal says nothing about why.
     */
    @Test
    void aWrongPasswordIsRefusedCleanlyAndWritesNothing() {
        ResponseEntity<String> response = restTemplate.exchange(
                ESCROW_HOLD,
                HttpMethod.PUT,
                new HttpEntity<>(body(30, WRONG_PASSWORD, true, null), authHeaders(actorToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("403 and not 401: the TOKEN is fine, and a 401 would sign the operator out over a typo")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody())
                .as("names neither the account nor which gate failed")
                .doesNotContain(actor.getUsername())
                .doesNotContain("password is incorrect")
                .contains("Nothing was changed");

        assertThat(escrowHoldPolicy.holdDays()).isEqualTo(7);
        assertThat(auditRowCount()).as("a failed attempt is not a change, so the audit table is untouched").isZero();
        verify(emailDispatcher, never()).dispatch(any());
        verify(emailDispatcher, never()).dispatchQuietly(any());
    }

    /**
     * The gate a password cannot supply: somebody who is exactly who they say they
     * are, submitting a form they did not mean to submit.
     */
    @Test
    void theExplicitAcknowledgementIsRequiredAndItsAbsenceIsRefusedTheSameWayAsItsDenial() {
        // Absent entirely - the accidental-form-post shape, and the case a bare
        // @AssertTrue would wave straight through because the spec says null is valid.
        Map<String, Object> withoutFlag = new LinkedHashMap<>();
        withoutFlag.put("holdDays", 30);
        withoutFlag.put("password", PASSWORD);

        assertThat(put(withoutFlag).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(escrowHoldPolicy.holdDays()).isEqualTo(7);

        // Present and false - somebody who read the box and did not tick it.
        ResponseEntity<String> denied = put(body(30, PASSWORD, false, null));
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(denied.getBody()).contains("confirm");

        assertThat(escrowHoldPolicy.holdDays()).isEqualTo(7);
        assertThat(auditRowCount()).isZero();
        verify(emailDispatcher, never()).dispatchQuietly(any());
    }

    /** A password alone is not enough, and an acknowledgement alone is not enough. Both directions. */
    @Test
    void neitherGateSubstitutesForTheOther() {
        assertThat(put(body(21, PASSWORD, false, null)).getStatusCode())
                .as("right password, no acknowledgement")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(put(body(21, WRONG_PASSWORD, true, null)).getStatusCode())
                .as("acknowledged, wrong password")
                .isEqualTo(HttpStatus.FORBIDDEN);

        Map<String, Object> noPassword = new LinkedHashMap<>();
        noPassword.put("holdDays", 21);
        noPassword.put("acknowledged", true);
        assertThat(put(noPassword).getStatusCode())
                .as("acknowledged, no password at all")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(escrowHoldPolicy.holdDays()).isEqualTo(7);
        assertThat(auditRowCount()).isZero();
    }

    /**
     * Everyone who is not a super admin, in every direction the acceptance bar asks
     * for: a tenant OWNER, a vendor, and nobody at all. Read AND write, because a
     * platform's money policy is not something a competitor gets to poll either.
     */
    @Test
    void aTenantUserAVendorAndAnAnonymousCallerAreAllRefusedOnBothTheReadAndTheWrite() {
        TenantLoginResponse company = signup("Not A Super Admin Ltd");
        TenantLoginResponse vendor = createVendorLogin();

        for (String token : List.of(company.tokens().accessToken(), vendor.tokens().accessToken())) {
            assertThat(restTemplate
                            .exchange(SETTINGS, HttpMethod.GET, new HttpEntity<>(authHeaders(token)), String.class)
                            .getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(restTemplate
                            .exchange(
                                    ESCROW_HOLD,
                                    HttpMethod.PUT,
                                    new HttpEntity<>(body(0, PASSWORD, true, null), authHeaders(token)),
                                    String.class)
                            .getStatusCode())
                    .as("and emphatically not the write side")
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        // No token at all.
        HttpStatusCode anonymousRead = restTemplate
                .exchange(SETTINGS, HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class)
                .getStatusCode();
        HttpStatusCode anonymousWrite = restTemplate
                .exchange(
                        ESCROW_HOLD,
                        HttpMethod.PUT,
                        new HttpEntity<>(body(0, PASSWORD, true, null), new HttpHeaders()),
                        String.class)
                .getStatusCode();
        assertThat(anonymousRead.is4xxClientError()).isTrue();
        assertThat(anonymousWrite.is4xxClientError()).isTrue();

        assertThat(escrowHoldPolicy.holdDays()).isEqualTo(7);
        assertThat(auditRowCount()).isZero();
    }

    // ---------------------------------------------------------------------------------
    // (c) Validation bounds
    // ---------------------------------------------------------------------------------

    /**
     * Zero is ALLOWED and means "payable the moment the buyer confirms" - the
     * behaviour that shipped before M9, and the documented way back to it without a
     * deploy. Negative is impossible and 91 is over the ceiling.
     */
    @Test
    void zeroIsAllowedNegativeIsImpossibleAndNinetyOneIsTooMany() {
        assertThat(put(body(0, PASSWORD, true, "Back to pay-on-confirmation")).getStatusCode())
                .as("zero is a coherent state, not a degenerate one")
                .isEqualTo(HttpStatus.OK);
        assertThat(escrowHoldPolicy.holdDays()).isZero();

        assertThat(put(body(-1, PASSWORD, true, null)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put(body(91, PASSWORD, true, null)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put(body(90, PASSWORD, true, null)).getStatusCode())
                .as("the ceiling itself is legal - it is a bound, not an exclusive limit")
                .isEqualTo(HttpStatus.OK);

        assertThat(escrowHoldPolicy.holdDays()).isEqualTo(90);
    }

    /** A missing holdDays must not silently mean zero, which on this field is "pay everybody now". */
    @Test
    void anOmittedHoldIsRefusedRatherThanTreatedAsZero() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("password", PASSWORD);
        body.put("acknowledged", true);

        assertThat(put(body).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(escrowHoldPolicy.holdDays()).isEqualTo(7);
    }

    /** The database says the same thing the DTO does, below every layer that could be refactored away. */
    @Test
    void theDatabaseAlsoRefusesAnOutOfRangeHold() {
        assertThatThrownBy(() -> jdbc.update("UPDATE vendor_settlement_settings SET escrow_hold_days = -1"))
                .hasMessageContaining("chk_vendor_settlement_settings_hold_days");
        assertThatThrownBy(() -> jdbc.update("UPDATE vendor_settlement_settings SET escrow_hold_days = 365"))
                .hasMessageContaining("chk_vendor_settlement_settings_hold_days");
    }

    // ---------------------------------------------------------------------------------
    // (d) The audit trail
    // ---------------------------------------------------------------------------------

    /** Who, from what, to what, when and why - and it cannot be edited afterwards. */
    @Test
    void everyChangeIsAuditedAndTheAuditTableIsAppendOnly() {
        put(body(10, PASSWORD, true, "Longer hold over the festive period"));
        put(body(3, PASSWORD, true, null));

        EscrowHoldSettingsResponse settings = readSettings();
        assertThat(settings.recentChanges()).hasSize(2);
        assertThat(settings.recentChanges().getFirst().newHoldDays())
                .as("newest first")
                .isEqualTo(3);
        assertThat(settings.recentChanges().getFirst().previousHoldDays())
                .as("both sides on one row, so a line is a complete statement")
                .isEqualTo(10);
        assertThat(settings.recentChanges().getFirst().reason())
                .as("no reason given renders as absent rather than as an empty string")
                .isNull();
        assertThat(settings.recentChanges().getLast().previousHoldDays()).isEqualTo(7);
        assertThat(settings.recentChanges().getLast().reason()).isEqualTo("Longer hold over the festive period");
        assertThat(settings.recentChanges())
                .allSatisfy(entry -> assertThat(entry.changedByUsername()).isEqualTo(actor.getUsername()));

        UUID rowId = jdbc.queryForObject(
                "SELECT id FROM vendor_settlement_settings_changes ORDER BY changed_at DESC LIMIT 1", UUID.class);

        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE vendor_settlement_settings_changes SET new_hold_days = 99 WHERE id = ?", rowId))
                .as("the ledger's own trigger, reused - an audit trail that can be edited is not one")
                .hasMessageContaining("append-only");
        assertThatThrownBy(
                        () -> jdbc.update("DELETE FROM vendor_settlement_settings_changes WHERE id = ?", rowId))
                .hasMessageContaining("append-only");
    }

    /** Setting the hold to the value it already has is a success that writes nothing and mails nobody. */
    @Test
    void aNoOpChangeIsAcceptedButRecordsNothing() {
        ResponseEntity<String> response = put(body(7, PASSWORD, true, "no change really"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(escrowHoldPolicy.holdDays()).isEqualTo(7);
        assertThat(auditRowCount())
                .as("an audit trail padded with entries that changed nothing is one nobody reads")
                .isZero();
        verify(emailDispatcher, never()).dispatchQuietly(any());

        // And the database refuses one even if something else tried to write it.
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO vendor_settlement_settings_changes "
                                + "(previous_hold_days, new_hold_days, changed_by_username, changed_at) "
                                + "VALUES (7, 7, 'someone', now())"))
                .hasMessageContaining("chk_vendor_settlement_settings_changes_actually_changed");
    }

    // ---------------------------------------------------------------------------------
    // (e) The email
    // ---------------------------------------------------------------------------------

    /**
     * All super admins, not just the actor, and SECURITY rather than TRANSACTIONAL.
     *
     * <p>The KIND assertion is the substance here rather than decoration. A super
     * admin has no {@code users} row and no {@code clients} row, so
     * {@code EmailEligibility}'s rules 4, 5 and 6 all miss and rule 7 drops anything
     * TRANSACTIONAL - silently, with one log line. If this message were TRANSACTIONAL
     * the test would still pass on "an email was dispatched" while not one super admin
     * ever received one, which is the exact trap {@code EmailKind}'s Javadoc describes.
     */
    @Test
    void theChangeEmailsEveryOtherSuperAdminAsASecurityNotice() {
        SuperAdmin colleague = createSuperAdmin();

        put(body(21, PASSWORD, true, "Tightening after a chargeback"));

        EmailMessage sent = onlyDispatched();
        assertThat(sent.kind())
                .as("TRANSACTIONAL would be dropped before SES for every super admin address - see EmailKind")
                .isEqualTo(EmailKind.SECURITY);
        assertThat(sent.to())
                .as("everybody who holds the credentials, including the actor - their copy is the receipt")
                .contains(actor.getUsername().toLowerCase(), colleague.getUsername().toLowerCase());

        assertThat(sent.subject()).contains("21 days");
        assertThat(sent.textBody())
                .contains("Previous hold: 7 days")
                .contains("New hold: 21 days")
                .contains(actor.getUsername())
                .contains("Tightening after a chargeback")
                .as("the retroactivity rule, in the message an operator will forward to a colleague")
                .contains("money confirmed from now on")
                .as("and the cadence consequence, because 21 > 14")
                .contains("full cycle later");
        assertThat(sent.htmlBody()).contains("keeps the hold it was confirmed under");
    }

    /** Zero gets words rather than "0 days", which in this one message reads like a missing field. */
    @Test
    void aZeroHoldIsDescribedInWordsInTheEmail() {
        put(body(0, PASSWORD, true, null));

        EmailMessage sent = onlyDispatched();
        assertThat(sent.subject()).contains("no hold");
        assertThat(sent.textBody()).contains("payable as soon as the buyer confirms");
    }

    /**
     * The guarantee that matters most in this class: a failing email does NOT roll
     * back a money-policy change that has already been made and audited.
     *
     * <p>{@code dispatchQuietly} is stubbed to throw, which defeats
     * {@code EmailDispatcher}'s own absorption and leaves only the service's guard
     * between the failure and the transaction. Without that guard the settings UPDATE
     * and its audit row would both roll back, and the platform would go on running the
     * OLD hold - a change that silently un-happened, which is worse than one nobody
     * was told about.
     */
    @Test
    void aFailingEmailDoesNotRollBackTheChangeOrItsAuditRow() {
        doThrow(new IllegalStateException("SES is on fire"))
                .when(emailDispatcher)
                .dispatchQuietly(any());
        try {
            ResponseEntity<String> response = put(body(12, PASSWORD, true, "email will fail"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(escrowHoldPolicy.holdDays())
                    .as("the change stands - the email is a courtesy on top of a decision already made")
                    .isEqualTo(12);
            assertThat(auditRowCount())
                    .as("and so does its record, which is the half a dispute will need")
                    .isEqualTo(1);
        } finally {
            // Restored explicitly rather than left to the spy's lifecycle: every later
            // test in this class asserts on dispatched mail.
            org.mockito.Mockito.reset(emailDispatcher);
        }
    }

    // ---------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------

    private SuperAdmin createSuperAdmin() {
        return superAdminRepository.saveAndFlush(SuperAdmin.builder()
                // An address, because super_admins has no email column and
                // EmailRecipients.forSuperAdmins reads the username - see its Javadoc.
                // A username that is not plausibly an address is discarded by
                // EmailMessage, which is a real deployment state and not what this
                // class is testing.
                .username(ADMIN_PREFIX + UUID.randomUUID() + "@procurepaddy.test")
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .build());
    }

    private String loginAsSuperAdmin(String username) {
        SuperAdminLoginResponse response = restTemplate.postForObject(
                "/api/superadmin/auth/login",
                new SuperAdminLoginRequest(username, PASSWORD),
                SuperAdminLoginResponse.class);
        assertThat(response).isNotNull();
        return response.tokens().accessToken();
    }

    /**
     * A vendor login, through signup plus a direct row edit. This class only needs a
     * VENDOR-role principal to prove it is refused, so going through the whole
     * onboarding flow would couple these assertions to another module's fixtures.
     */
    private TenantLoginResponse createVendorLogin() {
        TenantLoginResponse owner = signup("M9 Vendor Ltd");
        jdbc.update(
                "UPDATE clients SET client_type = 'VENDOR' WHERE slug = ?",
                owner.user().clientIdentifier());
        // Re-login so the token is minted against the vendor client type.
        return restTemplate.postForObject(
                "/api/auth/login",
                new LoginRequest(owner.user().clientIdentifier(), owner.user().username(), PASSWORD),
                TenantLoginResponse.class);
    }

    private TenantLoginResponse signup(String name) {
        String unique = UUID.randomUUID().toString();
        ClientSignupRequest request = new ClientSignupRequest(
                name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD);
        return restTemplate.postForObject("/api/clients/signup", request, TenantLoginResponse.class);
    }

    /**
     * Cleanup, using the append-only escape hatch V14 defined and V15's audit table
     * reuses.
     *
     * <p>Set and reset on ONE connection: a JdbcTemplate call takes whichever
     * connection the pool hands it, so a bare {@code SET} would very likely land on a
     * different connection from the DELETE that needs it. Nothing in the application
     * sets this, and nothing should.
     */
    private void cleanFixtures() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET app.ledger_maintenance = 'on'");
                statement.execute("DELETE FROM vendor_settlement_settings_changes");
                statement.execute("RESET app.ledger_maintenance");
            }
            return null;
        });
        jdbc.update("DELETE FROM super_admins WHERE username LIKE ?", ADMIN_PREFIX + "%");
    }

    // ---------------------------------------------------------------------------------
    // Drivers
    // ---------------------------------------------------------------------------------

    /** A request body. A map rather than the DTO, so a test can omit a field entirely. */
    private static Map<String, Object> body(int holdDays, String password, boolean acknowledged, String reason) {
        Map<String, Object> body = new HashMap<>();
        body.put("holdDays", holdDays);
        body.put("password", password);
        body.put("acknowledged", acknowledged);
        if (reason != null) {
            body.put("reason", reason);
        }
        return body;
    }

    private ResponseEntity<String> put(Map<String, Object> requestBody) {
        return restTemplate.exchange(
                ESCROW_HOLD, HttpMethod.PUT, new HttpEntity<>(requestBody, authHeaders(actorToken)), String.class);
    }

    private ResponseEntity<EscrowHoldSettingsResponse> putTyped(Map<String, Object> requestBody) {
        return restTemplate.exchange(
                ESCROW_HOLD,
                HttpMethod.PUT,
                new HttpEntity<>(requestBody, authHeaders(actorToken)),
                EscrowHoldSettingsResponse.class);
    }

    private EscrowHoldSettingsResponse readSettings() {
        ResponseEntity<EscrowHoldSettingsResponse> response = restTemplate.exchange(
                SETTINGS,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(actorToken)),
                EscrowHoldSettingsResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private int auditRowCount() {
        return jdbc.queryForObject("SELECT count(*) FROM vendor_settlement_settings_changes", Integer.class);
    }

    private EmailMessage onlyDispatched() {
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailDispatcher, atLeastOnce()).dispatch(captor.capture());
        List<EmailMessage> sent = captor.getAllValues().stream()
                .filter(message -> message.subject().contains("escrow hold"))
                .toList();
        assertThat(sent).as("exactly one escrow hold email").hasSize(1);
        return sent.getFirst();
    }

    private static HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
