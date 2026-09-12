package com.procurepal_services.stock_bridge_api.vendor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.SuperAdminLoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.SuperAdminLoginResponse;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.email.EmailDispatcher;
import com.procurepal_services.stock_bridge_api.email.EmailKind;
import com.procurepal_services.stock_bridge_api.email.EmailMessage;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.ClientType;
import com.procurepal_services.stock_bridge_api.entity.SuperAdmin;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.entity.VendorWaitlistApplication;
import com.procurepal_services.stock_bridge_api.entity.VendorWaitlistStatus;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.RoleRepository;
import com.procurepal_services.stock_bridge_api.repository.SuperAdminRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import com.procurepal_services.stock_bridge_api.repository.VendorWaitlistApplicationRepository;
import com.procurepal_services.stock_bridge_api.superadmin.dto.ApproveVendorApplicationRequest;
import com.procurepal_services.stock_bridge_api.superadmin.dto.CreateVendorRequest;
import com.procurepal_services.stock_bridge_api.superadmin.dto.RejectVendorApplicationRequest;
import com.procurepal_services.stock_bridge_api.superadmin.dto.SuperAdminVendorDetail;
import com.procurepal_services.stock_bridge_api.superadmin.dto.SuperAdminVendorSummary;
import com.procurepal_services.stock_bridge_api.superadmin.dto.VendorApplicationResponse;
import com.procurepal_services.stock_bridge_api.superadmin.dto.VendorWaitlistCounts;
import com.procurepal_services.stock_bridge_api.user.dto.CreateUserRequest;
import com.procurepal_services.stock_bridge_api.user.dto.RoleResponse;
import com.procurepal_services.stock_bridge_api.user.dto.UserSummaryResponse;
import com.procurepal_services.stock_bridge_api.vendor.waitlist.dto.VendorWaitlistApplicationRequest;
import com.procurepal_services.stock_bridge_api.vendor.waitlist.dto.VendorWaitlistApplicationResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The vendor onboarding path end to end: a stranger applies from a public page, a
 * super admin approves or rejects, and an approved business can sign in and sell.
 *
 * <p>Exercises the real HTTP + Spring Security filter chain against the local
 * docker-compose Postgres - see AuthIntegrationTest for why local Postgres over
 * Testcontainers. Requires `docker compose up -d` at the project root.
 *
 * <h2>Why the rate limit is raised here, and tested somewhere else</h2>
 * {@link com.procurepal_services.stock_bridge_api.vendor.waitlist.VendorWaitlistRateLimiter}
 * keys partly on the caller's remote address, and every request in this suite comes
 * from the same one. At the production default of three per hour the fourth test to
 * submit a form would get a 429 that has nothing to do with what it is asserting,
 * and the failure would move around as tests were reordered. Raising the limit here
 * keeps that out of the way; VendorWaitlistRateLimitIntegrationTest lowers it in its
 * own context and is the only place the limit itself is asserted.
 *
 * <h2>Why the dispatcher is spied rather than the sender</h2>
 * {@code EmailSender.sendAsync} runs after the transaction commits, on the email
 * executor, so asserting there means a timeout and a flaky test. {@code
 * EmailDispatcher.dispatch} is the last synchronous step - it runs on the request
 * thread inside the caller's transaction, which is exactly why per-recipient
 * eligibility is applied there (see that class) - so capturing its argument is
 * deterministic and shows the message as rendered, before eligibility narrows it.
 * That matters for what these tests are about: the KIND is the thing most likely to
 * be wrong, and a message dropped for being TRANSACTIONAL would never reach a spy
 * placed any later.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
@TestPropertySource(properties = "app.vendor-waitlist.submit-limit=500")
class VendorOnboardingIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private SuperAdminRepository superAdminRepository;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private VendorWaitlistApplicationRepository applicationRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoSpyBean
    private EmailDispatcher emailDispatcher;

    @BeforeEach
    void forgetEarlierEmails() {
        // The spy is one bean for the whole class, so without this every test sees
        // the mail every earlier test sent.
        clearInvocations(emailDispatcher);
    }

    // ========================================================================
    // (a) The public form.
    // ========================================================================

    /**
     * The happy path, and the two emails that must leave with it.
     *
     * <p>The KIND assertions are the substance here rather than decoration. An
     * applicant has no {@code users} row and no {@code clients} row, so
     * EmailEligibility's rules 4, 5 and 6 all miss and rule 7 drops anything
     * TRANSACTIONAL - silently, with one log line. If the acknowledgement were
     * TRANSACTIONAL this test would still pass on "an email was dispatched" and the
     * applicant would still hear nothing, forever. See VendorEmails.
     */
    @Test
    void submittingTheFormPersistsTheApplicationAndSendsBothEmails() {
        String unique = unique();
        String email = "apply-" + unique + "@example.com";

        ResponseEntity<VendorWaitlistApplicationResponse> response = restTemplate.postForEntity(
                "/api/vendor-waitlist",
                new VendorWaitlistApplicationRequest(
                        "Kano Packaging " + unique, email, "0803 111 2222",
                        "12 Murtala Way", null, "Kano", "Kano", "We supply cartons and shrink wrap."),
                VendorWaitlistApplicationResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("waitlist");

        VendorWaitlistApplication stored = onlyApplicationFor(email);
        assertThat(stored.getBusinessName()).isEqualTo("Kano Packaging " + unique);
        assertThat(stored.getContactPhone()).isEqualTo("0803 111 2222");
        assertThat(stored.getCity()).isEqualTo("Kano");
        assertThat(stored.getStatus()).isEqualTo(VendorWaitlistStatus.PENDING);
        // chk_vendor_waitlist_pending_is_unreviewed, from the outside.
        assertThat(stored.getReviewedBy()).isNull();
        assertThat(stored.getReviewedAt()).isNull();
        assertThat(stored.getApprovedClientId()).isNull();

        List<EmailMessage> sent = dispatchedEmails();

        EmailMessage toOps = onlyEmailWhoseSubjectContains(sent, "New vendor application");
        assertThat(toOps.to()).containsExactly("support@procurepaddy.com");
        assertThat(toOps.kind())
                .as("the ops inbox is a configured operator alias, which EmailEligibility rule 6 "
                        + "makes eligible for TRANSACTIONAL by name")
                .isEqualTo(EmailKind.TRANSACTIONAL);
        assertThat(toOps.htmlBody()).contains("Kano Packaging " + unique).contains("0803 111 2222");
        assertThat(toOps.htmlBody())
                .as("everything ops needs to research the applicant, without opening the admin panel")
                .contains("12 Murtala Way")
                .contains("cartons and shrink wrap");

        EmailMessage toApplicant = onlyEmailWhoseSubjectContains(sent, "We have your ProcurePal vendor application");
        assertThat(toApplicant.to()).containsExactly(email);
        assertThat(toApplicant.kind())
                .as("TRANSACTIONAL would be dropped by EmailEligibility rule 7 - the applicant has no "
                        + "user row and no client row, so nothing in the system has asked to mail them")
                .isEqualTo(EmailKind.VERIFICATION);
        assertThat(toApplicant.textBody())
                .as("the applicant must not go looking for a login they do not have")
                .contains("waitlist, not an account");
    }

    /**
     * The three required fields, each refused on its own, with the field named in
     * the message - which is the contract the React form maps to an input. A single
     * "Request validation failed." would leave the form unable to say which box is
     * wrong.
     */
    @Test
    void theThreeRequiredFieldsAreEachRefusedByName() {
        assertRejected(
                new VendorWaitlistApplicationRequest(
                        "  ", "someone@example.com", "0803 111 2222", null, null, null, null, null),
                "businessName");
        assertRejected(
                new VendorWaitlistApplicationRequest(
                        "No Email Ltd", "not-an-email", "0803 111 2222", null, null, null, null, null),
                "email");
        assertRejected(
                new VendorWaitlistApplicationRequest(
                        "No Phone Ltd", "someone@example.com", "  ", null, null, null, null, null),
                "contactPhone");
    }

    /**
     * A business that has applied before applies again, and cannot tell.
     *
     * <p>Both halves matter. The row is created - there is deliberately no unique
     * index on {@code email}, because a rejected applicant reapplying with better
     * information is the behaviour the schema was designed for. And the response is
     * byte-identical to the first one, because a differing response would turn a
     * public form into an oracle answering "does this business deal with
     * ProcurePaddy" for any address a competitor cares to type.
     */
    @Test
    void aRepeatApplicationIsAcceptedAndIndistinguishableFromTheFirst() {
        String email = "repeat-" + unique() + "@example.com";

        ResponseEntity<VendorWaitlistApplicationResponse> first = submit(email, "Repeat Co");
        ResponseEntity<VendorWaitlistApplicationResponse> second = submit(email, "Repeat Co");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getStatusCode()).isEqualTo(first.getStatusCode());
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(applicationRepository.findAllByEmailIgnoreCaseOrderByCreatedAtDesc(email)).hasSize(2);
    }

    /** No token, no tenant, no anything: the point of the endpoint is that a stranger can reach it. */
    @Test
    void theFormNeedsNoAuthentication() {
        ResponseEntity<VendorWaitlistApplicationResponse> response = submit(
                "anon-" + unique() + "@example.com", "Anonymous Applicant Ltd");

        assertThat(response.getStatusCode())
                .as("registered in SecurityConfig.PERMIT_ALL_PATHS, like /api/clients/signup")
                .isEqualTo(HttpStatus.ACCEPTED);
    }

    // ========================================================================
    // (b) Approval.
    // ========================================================================

    /**
     * The end-to-end acceptance criterion: an application becomes a vendor account
     * somebody can actually sign in to and sell from.
     */
    @Test
    void approvingCreatesAVendorClientWithExactlyOneVendorRoleUserWhoCanSignIn() {
        String superAdminToken = superAdminToken();
        String unique = unique();
        String email = "approve-" + unique + "@example.com";
        submit(email, "Lagos Fasteners " + unique);
        VendorWaitlistApplication application = onlyApplicationFor(email);
        clearInvocations(emailDispatcher);

        String username = "vendor-" + unique;
        String slug = "lagos-fasteners-" + unique;
        ResponseEntity<SuperAdminVendorDetail> response = restTemplate.exchange(
                "/api/superadmin/vendor-waitlist/" + application.getId() + "/approve",
                HttpMethod.POST,
                new HttpEntity<>(
                        new ApproveVendorApplicationRequest(
                                username, PASSWORD, PASSWORD, slug, new BigDecimal("0.1250"),
                                "Approved - cleared with finance."),
                        authHeaders(superAdminToken)),
                SuperAdminVendorDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        SuperAdminVendorDetail vendor = response.getBody();
        assertThat(vendor).isNotNull();
        assertThat(vendor.name()).isEqualTo("Lagos Fasteners " + unique);
        assertThat(vendor.slug()).isEqualTo(slug);
        assertThat(vendor.username()).isEqualTo(username);
        assertThat(vendor.commissionRate()).isEqualByComparingTo("0.1250");
        // Taken off the application, not retyped by the ops user - see
        // ApproveVendorApplicationRequest.
        assertThat(vendor.email()).isEqualTo(email);
        assertThat(vendor.city()).isEqualTo("Lagos");
        assertThat(vendor.applicationId()).isEqualTo(application.getId());

        Client client = clientRepository.findById(vendor.id()).orElseThrow();
        assertThat(client.getClientType()).isEqualTo(ClientType.VENDOR);
        assertThat(client.isVendor()).isTrue();
        assertThat(client.canSell()).isTrue();
        assertThat(client.isPlatformOwner())
                .as("client_type and is_platform_owner are orthogonal, and a vendor is never the operator")
                .isFalse();

        List<User> users = userRepository.findAllByClientId(client.getId());
        assertThat(users).as("a vendor has exactly ONE user account").hasSize(1);
        assertThat(users.getFirst().isRoot())
                .as("a vendor's single user IS its account holder, so root - which also means it cannot "
                        + "be demoted or deactivated into a non-account")
                .isTrue();
        // The role is asserted through a counting query rather than by reading
        // user.getRole().getName(). User.role is a FetchType.LAZY association and
        // this test holds a detached entity - there is no session out here, so
        // dereferencing the proxy is a LazyInitializationException. Making the
        // association eager to suit a test would change fetch behaviour for every
        // login in the application, which is the wrong direction entirely. The
        // counting form is also the stronger assertion: it says "exactly one ACTIVE
        // VENDOR-role user", which is the rule, rather than "this one row's role
        // happens to be VENDOR".
        assertThat(userRepository.countByClientIdAndRole_NameAndActiveTrue(client.getId(), "VENDOR"))
                .as("the vendor's one account carries the VENDOR role and nothing else does")
                .isEqualTo(1);

        // The application, stamped. chk_vendor_waitlist_approved_has_client from
        // the outside: APPROVED must name the client it created.
        VendorWaitlistApplication reviewed = applicationRepository.findById(application.getId()).orElseThrow();
        assertThat(reviewed.getStatus()).isEqualTo(VendorWaitlistStatus.APPROVED);
        assertThat(reviewed.getApprovedClientId()).isEqualTo(client.getId());
        assertThat(reviewed.getReviewedBy()).isNotNull();
        assertThat(reviewed.getReviewedAt()).isNotNull();
        assertThat(reviewed.getReviewNote()).isEqualTo("Approved - cleared with finance.");

        EmailMessage approval = onlyEmailWhoseSubjectContains(dispatchedEmails(), "approved to sell");
        assertThat(approval.to()).containsExactly(email);
        assertThat(approval.kind())
                .as("the vendor's brand-new user is unverified by definition, so TRANSACTIONAL would be "
                        + "refused by EmailEligibility rule 4 - the same deadlock AccountEmails.welcome has")
                .isEqualTo(EmailKind.VERIFICATION);
        assertThat(approval.htmlBody()).contains(username).contains("cleared with finance");
        assertThat(approval.htmlBody())
                .as("no email in this application ever carries a password")
                .doesNotContain(PASSWORD);

        // The whole point: they can trade.
        ResponseEntity<TenantLoginResponse> login = restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest(slug, username, PASSWORD), TenantLoginResponse.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody()).isNotNull();
        assertThat(login.getBody().user().role()).isEqualTo("VENDOR");
        assertThat(login.getBody().user().permissions())
                .contains("MANAGE_PRODUCTS", "MANAGE_MARKETPLACE", "VIEW_OWN_SALES_ANALYTICS")
                .doesNotContain("MANAGE_USERS", "PLACE_ORDERS");
    }

    /**
     * Approving twice is a clean 409, not a constraint violation - and, more
     * importantly, does not leave a second orphaned vendor account behind.
     *
     * <p>No CHECK on the table catches this:
     * {@code chk_vendor_waitlist_approved_has_client} only asks that an APPROVED row
     * names A client, which a second approval satisfies perfectly well while
     * repointing {@code approved_client_id} away from the first. The count assertion
     * is the one that would catch a regression here; the status code alone would
     * pass even if the guard ran after the client was created.
     */
    @Test
    void approvingTwiceIsRefusedAndCreatesNoSecondVendor() {
        String superAdminToken = superAdminToken();
        String unique = unique();
        String email = "twice-" + unique + "@example.com";
        submit(email, "Double Approve Ltd " + unique);
        VendorWaitlistApplication application = onlyApplicationFor(email);

        SuperAdminVendorDetail first = approve(superAdminToken, application.getId(), "v1-" + unique, null);
        long vendorsAfterFirst = clientRepository.countByClientType(ClientType.VENDOR);

        ResponseEntity<ApiError> second = restTemplate.exchange(
                "/api/superadmin/vendor-waitlist/" + application.getId() + "/approve",
                HttpMethod.POST,
                new HttpEntity<>(
                        new ApproveVendorApplicationRequest("v2-" + unique, PASSWORD, PASSWORD, null, null, null),
                        authHeaders(superAdminToken)),
                ApiError.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).isNotNull();
        assertThat(second.getBody().message()).contains("already been approved");
        assertThat(clientRepository.countByClientType(ClientType.VENDOR)).isEqualTo(vendorsAfterFirst);
        assertThat(applicationRepository.findById(application.getId()).orElseThrow().getApprovedClientId())
                .isEqualTo(first.id());
    }

    /** An already-rejected application cannot be approved: the guard is on PENDING, not on "not approved". */
    @Test
    void approvingAnAlreadyRejectedApplicationIsRefused() {
        String superAdminToken = superAdminToken();
        String unique = unique();
        String email = "rejected-then-approved-" + unique + "@example.com";
        submit(email, "Changed Our Mind Ltd " + unique);
        VendorWaitlistApplication application = onlyApplicationFor(email);
        reject(superAdminToken, application.getId(), "Not enough trading history.");

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/superadmin/vendor-waitlist/" + application.getId() + "/approve",
                HttpMethod.POST,
                new HttpEntity<>(
                        new ApproveVendorApplicationRequest("late-" + unique, PASSWORD, PASSWORD, null, null, null),
                        authHeaders(superAdminToken)),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().message()).contains("already been rejected");
    }

    // ========================================================================
    // (c) Rejection.
    // ========================================================================

    /** The note is the email, so both are asserted together - a stored note nobody receives is half a feature. */
    @Test
    void rejectingStampsTheApplicationAndEmailsTheReviewersNote() {
        String superAdminToken = superAdminToken();
        String unique = unique();
        String email = "reject-" + unique + "@example.com";
        submit(email, "Declined Traders " + unique);
        VendorWaitlistApplication application = onlyApplicationFor(email);
        clearInvocations(emailDispatcher);

        VendorApplicationResponse rejected =
                reject(superAdminToken, application.getId(), "We only onboard registered businesses for now.");

        assertThat(rejected.status()).isEqualTo(VendorWaitlistStatus.REJECTED);
        assertThat(rejected.reviewedBy()).isNotNull();
        assertThat(rejected.reviewedAt()).isNotNull();
        assertThat(rejected.approvedClientId())
                .as("nothing was created, and chk_vendor_waitlist_approved_has_client only binds APPROVED rows")
                .isNull();

        EmailMessage message = onlyEmailWhoseSubjectContains(dispatchedEmails(), "Your ProcurePal vendor application");
        assertThat(message.to()).containsExactly(email);
        assertThat(message.kind()).isEqualTo(EmailKind.VERIFICATION);
        assertThat(message.htmlBody()).contains("We only onboard registered businesses for now.");
        assertThat(message.htmlBody())
                .as("rejection is not permanent - reapplication is why there is no unique index on email")
                .contains("apply again");
    }

    /** A rejection with no note would mail somebody a decision with no reason. See RejectVendorApplicationRequest. */
    @Test
    void rejectingWithoutANoteIsRefused() {
        String superAdminToken = superAdminToken();
        String email = "no-note-" + unique() + "@example.com";
        submit(email, "No Note Ltd");
        VendorWaitlistApplication application = onlyApplicationFor(email);

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/superadmin/vendor-waitlist/" + application.getId() + "/reject",
                HttpMethod.POST,
                new HttpEntity<>(new RejectVendorApplicationRequest("   "), authHeaders(superAdminToken)),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("reviewNote");
    }

    // ========================================================================
    // (d) Direct creation, with no application behind it.
    // ========================================================================

    /**
     * The case the relaxed CHECK exists for: a business a super admin met in person,
     * with no email address at all.
     *
     * <p>{@code chk_clients_company_has_contact_email} still requires the column for
     * every COMPANY, so this passing proves the constraint was narrowed rather than
     * dropped - and the ClientSignupIntegrationTest suite, which creates COMPANY
     * rows with an address, proves the other half.
     */
    @Test
    void aSuperAdminCanCreateAVendorDirectlyWithNoEmailAddress() {
        String superAdminToken = superAdminToken();
        String unique = unique();
        String slug = "offline-vendor-" + unique;

        ResponseEntity<SuperAdminVendorDetail> response = restTemplate.exchange(
                "/api/superadmin/vendors",
                HttpMethod.POST,
                new HttpEntity<>(
                        new CreateVendorRequest(
                                "Offline Vendor " + unique, slug, null, "0805 999 0000",
                                "3 Balogun Street", null, "Ibadan", "Oyo", null,
                                // V28 payout/registration block - optional, and exercised here
                                // because this is the one creation path that can supply it (the
                                // waitlist form asks for none of it).
                                "Zenith Bank", "0011223344", "Offline Vendor Ltd", "RC 998877",
                                "offline-" + unique, PASSWORD, PASSWORD),
                        authHeaders(superAdminToken)),
                SuperAdminVendorDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        SuperAdminVendorDetail vendor = response.getBody();
        assertThat(vendor).isNotNull();
        assertThat(vendor.email()).isNull();
        assertThat(vendor.applicationId()).as("nobody applied - we went to them").isNull();
        assertThat(vendor.username()).isEqualTo("offline-" + unique);
        assertThat(vendor.bankName()).isEqualTo("Zenith Bank");
        assertThat(vendor.bankAccountNumber()).isEqualTo("0011223344");
        assertThat(vendor.bankAccountName()).isEqualTo("Offline Vendor Ltd");
        assertThat(vendor.cacNumber()).isEqualTo("RC 998877");

        Client client = clientRepository.findById(vendor.id()).orElseThrow();
        assertThat(client.getClientType()).isEqualTo(ClientType.VENDOR);
        assertThat(client.getAdminContactEmail())
                .as("a synthetic placeholder would be an address we invented and then mailed")
                .isNull();
        assertThat(userRepository.findAllByClientId(client.getId())).hasSize(1);

        ResponseEntity<TenantLoginResponse> login = restTemplate.postForEntity(
                "/api/auth/login",
                new LoginRequest(slug, "offline-" + unique, PASSWORD),
                TenantLoginResponse.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody().user().role()).isEqualTo("VENDOR");
    }

    /** A mistyped password would create an account nobody can sign into. Same guard as signup's. */
    @Test
    void aMismatchedPasswordConfirmationIsRefused() {
        String superAdminToken = superAdminToken();
        String unique = unique();

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/superadmin/vendors",
                HttpMethod.POST,
                new HttpEntity<>(
                        new CreateVendorRequest(
                                "Typo Vendor " + unique, null, null, "0805 000 1111",
                                null, null, null, null, null,
                                null, null, null, null,
                                "typo-" + unique, PASSWORD, "something-else-entirely"),
                        authHeaders(superAdminToken)),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("do not match");
    }

    // ========================================================================
    // (e) The vendor list and the vendor's own limits.
    // ========================================================================

    /**
     * The vendor list is vendors, and an id that belongs to a buying company is 404
     * on this surface rather than a COMPANY row rendered under a vendor heading.
     */
    @Test
    void theVendorListHoldsOnlyVendorsAndABuyersIdIsNotFoundThere() {
        String superAdminToken = superAdminToken();
        String unique = unique();
        SuperAdminVendorDetail vendor = createVendorDirectly(superAdminToken, unique);
        TenantLoginResponse buyer = signup("Ordinary Buyer");
        Client buyerClient = clientRepository.findBySlug(buyer.user().clientIdentifier()).orElseThrow();

        ResponseEntity<PageResponse<SuperAdminVendorSummary>> list = restTemplate.exchange(
                "/api/superadmin/vendors?search=" + unique,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(superAdminToken)),
                new ParameterizedTypeReference<>() {});

        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody().content()).extracting(SuperAdminVendorSummary::id).containsExactly(vendor.id());
        SuperAdminVendorSummary summary = list.getBody().content().getFirst();
        assertThat(summary.userCount())
                .as("a vendor has exactly one user; anything else here is the rule having broken")
                .isEqualTo(1);
        assertThat(summary.username()).isEqualTo("direct-" + unique);
        assertThat(summary.fromWaitlist()).isFalse();

        ResponseEntity<ApiError> buyerLookup = restTemplate.exchange(
                "/api/superadmin/vendors/" + buyerClient.getId(),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(superAdminToken)),
                ApiError.class);
        assertThat(buyerLookup.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * "A vendor has exactly one user account and cannot create staff", asserted from
     * the outside rather than trusted from the seeded permission set.
     *
     * <p>403 rather than 400: the VENDOR role does not hold MANAGE_USERS, so the
     * {@code @PreAuthorize} on UserController refuses before any request body is
     * looked at. That is the right layer for it - a vendor is not a user manager who
     * happens to be blocked, they are not a user manager.
     */
    @Test
    void aVendorsSingleUserCannotCreateAnotherUser() {
        String superAdminToken = superAdminToken();
        String unique = unique();
        SuperAdminVendorDetail vendor = createVendorDirectly(superAdminToken, unique);
        TenantLoginResponse vendorLogin = restTemplate.postForEntity(
                        "/api/auth/login",
                        new LoginRequest(vendor.slug(), vendor.username(), PASSWORD),
                        TenantLoginResponse.class)
                .getBody();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/users",
                HttpMethod.POST,
                new HttpEntity<>(
                        new CreateUserRequest(
                                "smuggled-" + unique,
                                PASSWORD,
                                roleRepository.findByName("STOREKEEPER").orElseThrow().getId(),
                                null,
                                null,
                                null,
                                null,
                                null),
                        authHeaders(vendorLogin.tokens().accessToken())),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(userRepository.findAllByClientId(vendor.id())).hasSize(1);
    }

    /**
     * The property M1 established and this module must preserve: no tenant OWNER can
     * create a VENDOR-role user.
     *
     * <p>Asserted at both layers, because either alone would let it back in. The
     * role picker must not offer VENDOR ({@code RoleCatalogService} filters to
     * {@code TenantRoles.ALL}), and asking for it anyway must be a clean 400 rather
     * than a 500 about an unseeded role ({@code TenantRoles.ALL} is the allow-list
     * both user services validate against). Super-admin vendor creation is the only
     * path that grants the role, and it names the constant directly rather than
     * going through that list - see SuperAdminVendorService.
     */
    @Test
    void noTenantOwnerCanCreateAVendorRoleUser() {
        TenantLoginResponse owner = signup("Would Be Vendor Maker");
        HttpHeaders headers = authHeaders(owner.tokens().accessToken());

        ResponseEntity<List<RoleResponse>> roles = restTemplate.exchange(
                "/api/roles", HttpMethod.GET, new HttpEntity<>(headers), new ParameterizedTypeReference<>() {});
        assertThat(roles.getBody())
                .as("GET /api/roles is a picker of ASSIGNABLE roles, so offering VENDOR would offer an "
                        + "option the very next request rejects")
                .extracting(RoleResponse::name)
                .doesNotContain("VENDOR");

        ResponseEntity<ApiError> attempt = restTemplate.exchange(
                "/api/users",
                HttpMethod.POST,
                new HttpEntity<>(
                        new CreateUserRequest(
                                "sneaky-" + unique(),
                                PASSWORD,
                                roleRepository.findByName("VENDOR").orElseThrow().getId(),
                                null,
                                null,
                                null,
                                null,
                                null),
                        headers),
                ApiError.class);

        assertThat(attempt.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<PageResponse<UserSummaryResponse>> users = restTemplate.exchange(
                "/api/users", HttpMethod.GET, new HttpEntity<>(headers), new ParameterizedTypeReference<>() {});
        assertThat(users.getBody().content())
                .as("the tenant is left with only the user signup created")
                .hasSize(1);
    }

    /** The nav badge's number. Pending is the one anybody looks at, so it is the one asserted to move. */
    @Test
    void theCountsEndpointTracksPendingApplications() {
        String superAdminToken = superAdminToken();
        VendorWaitlistCounts before = counts(superAdminToken);

        submit("counts-" + unique() + "@example.com", "Counted Ltd");

        assertThat(counts(superAdminToken).pending()).isEqualTo(before.pending() + 1);
    }

    // ------------------------------------------------------------------------
    // Helpers.
    // ------------------------------------------------------------------------

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private ResponseEntity<VendorWaitlistApplicationResponse> submit(String email, String businessName) {
        return restTemplate.postForEntity(
                "/api/vendor-waitlist",
                new VendorWaitlistApplicationRequest(
                        businessName, email, "0803 111 2222", "1 Test Road", null, "Lagos", "Lagos", null),
                VendorWaitlistApplicationResponse.class);
    }

    private void assertRejected(VendorWaitlistApplicationRequest request, String expectedField) {
        ResponseEntity<ApiError> response =
                restTemplate.postForEntity("/api/vendor-waitlist", request, ApiError.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains(expectedField);
    }

    private VendorWaitlistApplication onlyApplicationFor(String email) {
        List<VendorWaitlistApplication> found =
                applicationRepository.findAllByEmailIgnoreCaseOrderByCreatedAtDesc(email);
        assertThat(found).hasSize(1);
        return found.getFirst();
    }

    private SuperAdminVendorDetail approve(String token, UUID applicationId, String username, String slug) {
        return restTemplate
                .exchange(
                        "/api/superadmin/vendor-waitlist/" + applicationId + "/approve",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                new ApproveVendorApplicationRequest(username, PASSWORD, PASSWORD, slug, null, null),
                                authHeaders(token)),
                        SuperAdminVendorDetail.class)
                .getBody();
    }

    private VendorApplicationResponse reject(String token, UUID applicationId, String note) {
        return restTemplate
                .exchange(
                        "/api/superadmin/vendor-waitlist/" + applicationId + "/reject",
                        HttpMethod.POST,
                        new HttpEntity<>(new RejectVendorApplicationRequest(note), authHeaders(token)),
                        VendorApplicationResponse.class)
                .getBody();
    }

    private SuperAdminVendorDetail createVendorDirectly(String token, String unique) {
        return restTemplate
                .exchange(
                        "/api/superadmin/vendors",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                new CreateVendorRequest(
                                        "Direct Vendor " + unique, "direct-vendor-" + unique, null, "0805 222 3333",
                                        null, null, null, null, null,
                                        null, null, null, null,
                                        "direct-" + unique, PASSWORD, PASSWORD),
                                authHeaders(token)),
                        SuperAdminVendorDetail.class)
                .getBody();
    }

    private VendorWaitlistCounts counts(String token) {
        return restTemplate
                .exchange(
                        "/api/superadmin/vendor-waitlist/counts",
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(token)),
                        VendorWaitlistCounts.class)
                .getBody();
    }

    /** Everything handed to the dispatcher on the request thread since the last reset. */
    private List<EmailMessage> dispatchedEmails() {
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailDispatcher, atLeastOnce()).dispatch(captor.capture());
        return captor.getAllValues();
    }

    /**
     * Matched by subject rather than by position, because a flow that sends two
     * emails does not promise an order and a test that assumed one would fail for a
     * reason unrelated to what it is checking.
     */
    private static EmailMessage onlyEmailWhoseSubjectContains(List<EmailMessage> sent, String fragment) {
        List<EmailMessage> matching =
                sent.stream().filter(message -> message.subject().contains(fragment)).toList();
        assertThat(matching).as("exactly one email whose subject contains \"%s\"", fragment).hasSize(1);
        return matching.getFirst();
    }

    private String superAdminToken() {
        String uniqueUsername = "superadmin-" + UUID.randomUUID();
        superAdminRepository.save(SuperAdmin.builder()
                .username(uniqueUsername)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .build());
        SuperAdminLoginResponse response = restTemplate.postForObject(
                "/api/superadmin/auth/login",
                new SuperAdminLoginRequest(uniqueUsername, PASSWORD),
                SuperAdminLoginResponse.class);
        return response.tokens().accessToken();
    }

    private TenantLoginResponse signup(String name) {
        String unique = UUID.randomUUID().toString();
        return restTemplate.postForObject(
                "/api/clients/signup",
                new ClientSignupRequest(
                        name + " " + unique.substring(0, 8), null,
                        "owner-" + unique + "@example.com", PASSWORD, PASSWORD),
                TenantLoginResponse.class);
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    /** Minimal shape to deserialize Spring Data's Page<T> JSON - same helper the super-admin suite uses. */
    private record PageResponse<T>(List<T> content) {
    }
}
