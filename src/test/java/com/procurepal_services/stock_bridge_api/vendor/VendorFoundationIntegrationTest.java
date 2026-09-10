package com.procurepal_services.stock_bridge_api.vendor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.ClientType;
import com.procurepal_services.stock_bridge_api.entity.CompanyVendor;
import com.procurepal_services.stock_bridge_api.entity.CompanyVendorKind;
import com.procurepal_services.stock_bridge_api.entity.Permission;
import com.procurepal_services.stock_bridge_api.entity.Role;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.profile.dto.ProfileResponse;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.CompanyVendorRepository;
import com.procurepal_services.stock_bridge_api.repository.RoleRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantAwareEntity;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.UUID;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The vendor foundation laid down by V11: the two coherence CHECKs on
 * company_vendors, that directory's tenant isolation, the seeded VENDOR role's
 * permission set, the orders.seller_client_id backfill, and VendorGuard.
 *
 * Runs against the local docker-compose Postgres like every other integration test
 * here - see AuthIntegrationTest for why local Postgres over Testcontainers.
 * Requires `docker compose up -d` at the project root.
 *
 * The guard is exercised through a real HTTP round trip for the same reason
 * MarketplaceFoundationIntegrationTest exercises PlatformOwnerGuard that way: what
 * is worth proving is not "the method throws" but "the caller gets a 403 with an
 * ApiError body", which depends on VendorAccessExceptionHandler being global and
 * would silently regress to a 500 if it were ever scoped to one controller. The
 * vendor controllers themselves belong to later modules, so this test stands up
 * minimal probe controllers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
@Import(VendorFoundationIntegrationTest.VendorProbeConfiguration.class)
class VendorFoundationIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final String VENDOR_PROBE_PATH = "/api/vendor/_vendor-probe";
    private static final String SELLER_PROBE_PATH = "/api/vendor/_seller-probe";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private CompanyVendorRepository companyVendorRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private VendorGuard vendorGuard;

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------------
    // (a) company_vendors: the two kinds have to stay coherent, and the database
    //     is what makes them. These are row-local conditions, so there is no
    //     excuse for enforcing them anywhere weaker than a CHECK - and the failure
    //     mode if service code owned them is silent, which is why they are worth
    //     a test that writes the bad row rather than one that calls a validator.
    // ------------------------------------------------------------------------

    /**
     * VERIFIED asserts a fact about the platform - "you traded with them" - so it
     * must name the account it is asserting it about. Without the CHECK such a row
     * looks like an ordinary directory entry and simply never links to the seller.
     */
    @Test
    @Transactional
    void aVerifiedDirectoryEntryWithNoPlatformClientIsRejectedByTheDatabase() {
        UUID buyerId = someBuyerId();
        TenantContext.set(buyerId);

        CompanyVendor incoherent = CompanyVendor.builder()
                .vendorKind(CompanyVendorKind.VERIFIED)
                .platformClientId(null)
                .name("Verified With No Seller")
                .active(true)
                .build();

        // saveAndFlush, not save: the constraint has to be hit now rather than at
        // some unrelated flush later, which is the whole point of asserting that
        // the database enforces this.
        assertThatThrownBy(() -> companyVendorRepository.saveAndFlush(incoherent))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_company_vendors_verified_shape");
    }

    /**
     * The other direction. An EXTERNAL row carrying a platform client id claims a
     * relationship with a real vendor account that the buyer simply typed in
     * themselves - a company could otherwise mark any seller on the platform as
     * "their" supplier without ever having bought anything.
     */
    @Test
    @Transactional
    void anExternalDirectoryEntryThatNamesAPlatformClientIsRejectedByTheDatabase() {
        UUID buyerId = someBuyerId();
        UUID platformOwnerId = platformOwnerId();
        TenantContext.set(buyerId);

        CompanyVendor incoherent = CompanyVendor.builder()
                .vendorKind(CompanyVendorKind.EXTERNAL)
                .platformClientId(platformOwnerId)
                .name("External Pretending To Be On Platform")
                .contactPhone("+2348030000001")
                .active(true)
                .build();

        assertThatThrownBy(() -> companyVendorRepository.saveAndFlush(incoherent))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_company_vendors_external_shape");
    }

    /** The same CHECK's other half: a hand-typed supplier with no phone number is not a record of anything. */
    @Test
    @Transactional
    void anExternalDirectoryEntryWithNoContactPhoneIsRejectedByTheDatabase() {
        TenantContext.set(someBuyerId());

        CompanyVendor incoherent = CompanyVendor.builder()
                .vendorKind(CompanyVendorKind.EXTERNAL)
                .name("External With No Phone")
                .active(true)
                .build();

        assertThatThrownBy(() -> companyVendorRepository.saveAndFlush(incoherent))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_company_vendors_external_shape");
    }

    /**
     * The control. Without this, all three assertions above would still pass if the
     * table rejected every row for some entirely unrelated reason.
     */
    @Test
    @Transactional
    void aCoherentRowOfEitherKindIsAccepted() {
        UUID buyerId = someBuyerId();
        TenantContext.set(buyerId);

        assertThatCode(() -> companyVendorRepository.saveAndFlush(CompanyVendor.builder()
                        .vendorKind(CompanyVendorKind.EXTERNAL)
                        .name("Local Miller " + UUID.randomUUID())
                        .contactPhone("+2348030000002")
                        .addressLine1("14 Mill Road")
                        .city("Ibadan")
                        .state("Oyo")
                        .active(true)
                        .build()))
                .doesNotThrowAnyException();

        CompanyVendor verified = companyVendorRepository.saveAndFlush(CompanyVendor.builder()
                .vendorKind(CompanyVendorKind.VERIFIED)
                .platformClientId(platformOwnerId())
                .name("ProcurePal")
                .active(true)
                .build());

        // The VERIFIED row is the platform's assertion, not the buyer's note, so the
        // buyer may deactivate it but not rewrite it.
        assertThat(verified.isEditableByOwningCompany()).isFalse();
    }

    // ------------------------------------------------------------------------
    // (b) tenant isolation. A directory entry is one company's private
    //     bookkeeping about a supplier - its notes, and in later modules what it
    //     last paid - so both layers have to hold, exactly as they do for users
    //     in TenantIsolationIntegrationTest.
    // ------------------------------------------------------------------------

    @Test
    @Transactional
    void oneCompanysVendorDirectoryIsInvisibleToAnother() {
        Client buyerA = createBareClient("Directory Buyer A", ClientType.COMPANY);
        Client buyerB = createBareClient("Directory Buyer B", ClientType.COMPANY);

        CompanyVendor vendorOfA = saveExternalVendor(buyerA.getId(), "A's Diesel Supplier");
        CompanyVendor vendorOfB = saveExternalVendor(buyerB.getId(), "B's Diesel Supplier");

        // client_id is assigned from TenantContext by @PrePersist and is never
        // settable by a caller - proving that here is proving the row cannot be
        // planted in someone else's directory.
        assertThat(vendorOfA.getClientId()).isEqualTo(buyerA.getId());
        assertThat(vendorOfB.getClientId()).isEqualTo(buyerB.getId());

        // Layer 1: the Hibernate filter, enabled the way TenantResolutionFilter does
        // it for a real request. findAll() carries no explicit predicate, so the
        // filter alone must supply one.
        TenantContext.set(buyerA.getId());
        entityManager.unwrap(Session.class)
                .enableFilter(TenantAwareEntity.TENANT_FILTER_NAME)
                .setParameter(TenantAwareEntity.TENANT_FILTER_PARAM, buyerA.getId());

        List<UUID> visibleToA =
                companyVendorRepository.findAll().stream().map(CompanyVendor::getId).toList();
        assertThat(visibleToA).contains(vendorOfA.getId()).doesNotContain(vendorOfB.getId());

        // Layer 2: with the filter off, the explicit client_id predicate must still
        // refuse to hand B's row to A.
        entityManager.unwrap(Session.class).disableFilter(TenantAwareEntity.TENANT_FILTER_NAME);
        assertThat(companyVendorRepository.findByIdAndClientIdAndActiveTrue(vendorOfB.getId(), buyerA.getId()))
                .isEmpty();
        assertThat(companyVendorRepository.findByIdAndClientIdAndActiveTrue(vendorOfA.getId(), buyerA.getId()))
                .isPresent();
    }

    // ------------------------------------------------------------------------
    // (c) the seeded VENDOR role.
    // ------------------------------------------------------------------------

    /**
     * Asserted straight off the roles table rather than through /api/roles, unlike
     * every other role in RolePermissionMatrixIntegrationTest. That endpoint serves
     * ASSIGNABLE roles and VENDOR deliberately is not one - see TenantRoles.VENDOR -
     * so the table is the only place its grants are observable.
     *
     * The two negative assertions are the point of the test. MANAGE_USERS is the
     * grant that would break "a vendor has exactly one user account and cannot
     * create staff", and PLACE_ORDERS is the one that would break "vendors sell,
     * they do not buy". Their absence is load-bearing, so it is asserted rather
     * than left to be inferred from the positive list.
     */
    // @Transactional only to keep a session open for Role.permissions, which is
    // lazy; this test reads and writes nothing.
    @Test
    @Transactional
    void theVendorRoleSellsAndCannotBuyOrCreateStaff() {
        Role vendorRole = roleRepository
                .findByName("VENDOR")
                .orElseThrow(() -> new IllegalStateException("VENDOR role not seeded - run the Flyway migrations"));

        List<String> codes = vendorRole.getPermissions().stream()
                .map(Permission::getCode)
                .sorted()
                .toList();

        assertThat(codes)
                .containsExactly(
                        // A vendor's single user IS its account holder, and V7 exists so
                        // an account holder can fix their own details without a ticket.
                        "MANAGE_COMPANY_PROFILE",
                        // "manage pickup addresses" - vendors reuse delivery_addresses
                        // for those, since a vendor is a client like any other.
                        "MANAGE_DELIVERY_ADDRESSES",
                        // A seller holds real stock and the marketplace refuses orders
                        // that exceed it, so a vendor who cannot record what arrived
                        // cannot trade.
                        "MANAGE_INVENTORY",
                        // List and unlist THEIR OWN products. The code also covers
                        // categories and marketplace settings, which are ProcurePal-only;
                        // the guards are what keep a vendor off those, exactly as they
                        // already do for every tenant OWNER.
                        "MANAGE_MARKETPLACE",
                        // "see orders placed with them", scoped by seller_client_id
                        // rather than by this grant.
                        "MANAGE_MARKETPLACE_ORDERS",
                        "MANAGE_PRODUCTS",
                        // V27. Split out of MANAGE_INVENTORY, which this role already held -
                        // see StockController.
                        "STOCK_IN",
                        "STOCK_OUT",
                        "VIEW_ANALYTICS",
                        // V11's new, deliberately distinct code. NOT
                        // VIEW_MARKETPLACE_ANALYTICS - see below.
                        "VIEW_OWN_SALES_ANALYTICS",
                        "VIEW_PRODUCTS");

        assertThat(codes)
                .as("a vendor has exactly one user account and cannot create staff")
                .doesNotContain("MANAGE_USERS", "MANAGE_ROLES");
        assertThat(codes).as("vendors sell; they do not buy").doesNotContain("PLACE_ORDERS", "BROWSE_MARKETPLACE");
        assertThat(codes)
                .as("VIEW_MARKETPLACE_ANALYTICS is the OPERATOR's analytics code, and its routes carry"
                        + " the buyer-identity half - named customers, new/repeat rates, category mix -"
                        + " which a vendor must never see about its buyers. Since M6 those routes no"
                        + " longer span every seller either, but the code is still not a vendor's.")
                .doesNotContain("VIEW_MARKETPLACE_ANALYTICS");
        assertThat(codes)
                .as("the buyer-side supplier directory belongs to companies that buy")
                .doesNotContain("VIEW_VENDORS", "MANAGE_VENDORS");
    }

    // ------------------------------------------------------------------------
    // (d) the orders.seller_client_id backfill.
    // ------------------------------------------------------------------------

    /**
     * Every order that existed when V11 ran was sold by ProcurePal, because
     * ProcurePal was the only seller there had ever been - which is exactly what
     * made the column safe to declare NOT NULL.
     *
     * Scoped to rows older than the migration itself, read out of
     * flyway_schema_history, rather than to every row in the table. The suite and
     * the local app both create orders continuously, and once a later module lets a
     * vendor sell, orders with another seller are correct and expected; an
     * assertion over the whole table would then start failing for a reason that has
     * nothing to do with the backfill it is meant to be about.
     */
    @Test
    void everyOrderOlderThanTheMigrationNamesThePlatformOwnerAsSeller() {
        UUID platformOwnerId = platformOwnerId();

        Object[] counts = (Object[]) entityManager
                .createNativeQuery(
                        """
                        SELECT count(*),
                               count(*) FILTER (WHERE o.seller_client_id = c.id)
                        FROM orders o
                        CROSS JOIN clients c
                        WHERE c.id = :ownerId
                          AND o.created_at < (SELECT installed_on
                                              FROM flyway_schema_history
                                              WHERE version = '11')
                        """)
                .setParameter("ownerId", platformOwnerId)
                .getSingleResult();

        long total = ((Number) counts[0]).longValue();
        long ownedByPlatformOwner = ((Number) counts[1]).longValue();

        // Nothing to prove on a database whose orders all postdate the migration -
        // a brand new one, say. Saying so beats a vacuous green tick.
        if (total == 0) {
            return;
        }
        assertThat(ownedByPlatformOwner)
                .as("orders predating V11 that name the platform owner as seller, out of %d", total)
                .isEqualTo(total);
    }

    /** The NOT NULL itself: no order anywhere may be missing a seller. */
    @Test
    void noOrderIsWithoutASeller() {
        Number orphans = (Number) entityManager
                .createNativeQuery("SELECT count(*) FROM orders WHERE seller_client_id IS NULL")
                .getSingleResult();
        assertThat(orphans.longValue()).isZero();
    }

    // ------------------------------------------------------------------------
    // (e) VendorGuard.
    // ------------------------------------------------------------------------

    /**
     * A buying company's OWNER holds MANAGE_MARKETPLACE - every OWNER does, because
     * permissions hang off global roles - so @PreAuthorize alone would let them
     * straight through a vendor surface. The 403 can only come from the guard.
     */
    @Test
    void anOrdinaryCompanyIsRefusedOnAVendorSurface() {
        TenantLoginResponse company = signup("Not A Vendor Co");
        assertThat(company.user().permissions()).contains("MANAGE_MARKETPLACE");
        assertThat(company.user().clientType()).isEqualTo(ClientType.COMPANY);

        ProbeResult refusal = probe(VENDOR_PROBE_PATH, company);
        assertThat(refusal.status).isEqualTo(403);
        assertThat(refusal.body).contains("vendor accounts");

        // And the seller surface refuses them too: a buying company sells nothing.
        assertThat(probe(SELLER_PROBE_PATH, company).status).isEqualTo(403);
    }

    @Test
    void aVendorReachesBothVendorAndSellerSurfaces() {
        TenantLoginResponse vendor = loginAsNewVendor("Foundation Test Vendor");
        assertThat(vendor.user().clientType()).isEqualTo(ClientType.VENDOR);

        assertThat(probe(VENDOR_PROBE_PATH, vendor).status).isEqualTo(200);
        assertThat(probe(SELLER_PROBE_PATH, vendor).status).isEqualTo(200);
    }

    /**
     * The distinction most likely to be got wrong, pinned down: ProcurePal is a
     * COMPANY that owns the platform and also sells, so requireSeller() must accept
     * it and requireVendor() must not. Writing requireVendor() on a catalogue or
     * order-queue surface is the easy way to lock the platform owner out of its own
     * marketplace.
     */
    @Test
    @Transactional
    void thePlatformOwnerMaySellButIsNotAVendor() {
        Client procurePal = clientRepository.findByPlatformOwnerTrue().orElseThrow();
        assertThat(procurePal.getClientType())
                .as("client_type and is_platform_owner are orthogonal")
                .isEqualTo(ClientType.COMPANY);

        TenantContext.set(procurePal.getId());

        assertThat(vendorGuard.canCurrentTenantSell()).isTrue();
        assertThat(vendorGuard.isCurrentTenantVendor()).isFalse();
        assertThat(vendorGuard.requireSeller().getId()).isEqualTo(procurePal.getId());
        assertThatThrownBy(() -> vendorGuard.requireVendor()).isInstanceOf(VendorNotAllowedException.class);
    }

    /** No tenant context at all - a public request - is refused, not treated as permissive. */
    @Test
    void anAbsentTenantContextIsRefused() {
        TenantContext.clear();
        assertThat(vendorGuard.isCurrentTenantVendor()).isFalse();
        assertThat(vendorGuard.canCurrentTenantSell()).isFalse();
        assertThatThrownBy(() -> vendorGuard.requireVendor()).isInstanceOf(VendorNotAllowedException.class);
        assertThatThrownBy(() -> vendorGuard.requireSeller()).isInstanceOf(VendorNotAllowedException.class);
    }

    // ------------------------------------------------------------------------
    // (f) the clientType claim. Login and /api/me must never disagree - the
    //     frontend reads one on login and the other on reload, and a difference
    //     between them shows a nav group to somebody the API will refuse.
    // ------------------------------------------------------------------------

    @Test
    void loginAndMeAgreeOnClientTypeForAVendor() {
        TenantLoginResponse vendor = loginAsNewVendor("Claim Consistency Vendor");

        ProfileResponse me = restTemplate.exchange(
                        "/api/me",
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(vendor)),
                        ProfileResponse.class)
                .getBody();

        assertThat(me).isNotNull();
        assertThat(me.clientType()).isEqualTo(ClientType.VENDOR).isEqualTo(vendor.user().clientType());
        // Orthogonal, and a vendor is emphatically not the platform owner.
        assertThat(me.platformOwner()).isFalse();
    }

    // ------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------

    /**
     * Status plus raw body. Deliberately NOT deserialized into {@link ApiError}: a
     * permitted call answers with a plain slug string, so a typed exchange would
     * fail on the success case and hide the very outcome being asserted. The 403
     * body is checked by substring for the same reason - what matters is that a
     * refusal carries the guard's message rather than an empty 500 page.
     */
    private record ProbeResult(int status, String body) {}

    private ProbeResult probe(String path, TenantLoginResponse as) {
        ResponseEntity<String> response =
                restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(authHeaders(as)), String.class);
        return new ProbeResult(
                response.getStatusCode().value(), response.getBody() == null ? "" : response.getBody());
    }

    private UUID platformOwnerId() {
        return clientRepository.findByPlatformOwnerTrue().orElseThrow().getId();
    }

    /** Any client that is not the platform owner will do for a directory owner. */
    /**
     * A buying company for these fixtures to own rows under - freshly created, NOT
     * the seeded {@code demo} tenant this used to return.
     *
     * <h2>Why that change was forced</h2>
     * There is no Testcontainers here: every integration test runs against one
     * shared local Postgres, which is also the database a developer runs the app
     * against, so anything committed by anybody is still there on the next run. The
     * seeded {@code demo} tenant is the single most shared row in that database.
     *
     * <p>{@code uq_company_vendors_client_id_platform_client_id} allows a company
     * exactly one directory entry per platform vendor, and the marketplace now
     * creates a VERIFIED entry automatically the first time a buyer orders from a
     * seller. {@code demo} has therefore acquired a committed
     * {@code (demo, procurepal)} row from ordinary use, and the acceptance test
     * below - which inserts precisely that pair - started failing on a constraint
     * that was doing its job correctly. The test was asserting against a tenant
     * whose state it did not own.
     *
     * <p>A fresh client per call costs one insert that rolls back with the test
     * transaction, and removes the dependency on seed data entirely. The three
     * rejection tests above are unaffected either way - they assert that the
     * database refuses a row, so they never needed a particular tenant, only a
     * valid one.
     *
     * <p>Worth flagging beyond this method: the same hazard applies to any future
     * test that writes a uniquely-constrained row under a shared tenant, and the
     * general fix (Testcontainers, or a per-run schema) is bigger than this file.
     */
    private UUID someBuyerId() {
        return createBareClient("Directory Fixture Buyer", ClientType.COMPANY).getId();
    }

    private CompanyVendor saveExternalVendor(UUID ownerId, String name) {
        TenantContext.set(ownerId);
        try {
            return companyVendorRepository.saveAndFlush(CompanyVendor.builder()
                    .vendorKind(CompanyVendorKind.EXTERNAL)
                    .name(name + " " + UUID.randomUUID())
                    .contactPhone("+2348030000003")
                    .active(true)
                    .build());
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * A client straight through the repository, bypassing ClientSignupService - the
     * only way to get a VENDOR row today, since a vendor is created by a super
     * admin and that path belongs to a later module. Note adminContactEmail is left
     * null for the vendor, which V11 made legal for exactly this case (a super
     * admin adding a vendor they met in person) and which the CHECK still forbids
     * for a COMPANY.
     */
    private Client createBareClient(String name, ClientType type) {
        String slug = name.toLowerCase().replace(" ", "-") + "-" + UUID.randomUUID();
        return clientRepository.saveAndFlush(Client.builder()
                .name(name)
                .slug(slug)
                .adminContactEmail(type == ClientType.VENDOR ? null : slug + "@example.com")
                .clientType(type)
                .active(true)
                .build());
    }

    private TenantLoginResponse loginAsNewVendor(String name) {
        Client vendorClient = createBareClient(name, ClientType.VENDOR);
        Role vendorRole = roleRepository.findByName("VENDOR").orElseThrow();

        String username = "vendor-" + UUID.randomUUID();
        TenantContext.set(vendorClient.getId());
        try {
            userRepository.saveAndFlush(User.builder()
                    .username(username)
                    .passwordHash(passwordEncoder.encode(PASSWORD))
                    .role(vendorRole)
                    .active(true)
                    .root(true)
                    .build());
        } finally {
            TenantContext.clear();
        }

        return restTemplate.postForObject(
                "/api/auth/login",
                new LoginRequest(vendorClient.getSlug(), username, PASSWORD),
                TenantLoginResponse.class);
    }

    private TenantLoginResponse signup(String name) {
        String unique = UUID.randomUUID().toString();
        ClientSignupRequest request = new ClientSignupRequest(
                name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD);
        return restTemplate.postForObject("/api/clients/signup", request, TenantLoginResponse.class);
    }

    private HttpHeaders authHeaders(TenantLoginResponse response) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(response.tokens().accessToken());
        return headers;
    }

    /**
     * Two probes rather than one, because the guard's two methods answer different
     * questions and the difference between them is the thing worth protecting.
     * Deliberately carries no @PreAuthorize: this test is about the guard, and a
     * permission check in front of it would make a 403 ambiguous about which gate
     * produced it.
     */
    @TestConfiguration
    static class VendorProbeConfiguration {

        @Bean
        VendorProbeController vendorProbeController(VendorGuard vendorGuard) {
            return new VendorProbeController(vendorGuard);
        }
    }

    @RestController
    static class VendorProbeController {

        private final VendorGuard vendorGuard;

        VendorProbeController(VendorGuard vendorGuard) {
            this.vendorGuard = vendorGuard;
        }

        @GetMapping(VENDOR_PROBE_PATH)
        String vendorOnly() {
            return vendorGuard.requireVendor().getSlug();
        }

        @GetMapping(SELLER_PROBE_PATH)
        String sellersOnly() {
            return vendorGuard.requireSeller().getSlug();
        }
    }
}
