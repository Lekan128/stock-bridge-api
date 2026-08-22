package com.procurepal_services.stock_bridge_api.marketplace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.entity.Branch;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.MarketplaceSettings;
import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.PaymentMethod;
import com.procurepal_services.stock_bridge_api.entity.PaymentStatus;
import com.procurepal_services.stock_bridge_api.repository.BranchRepository;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.MarketplaceSettingsRepository;
import com.procurepal_services.stock_bridge_api.repository.OrderRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantAwareEntity;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The marketplace foundation: the platform-owner guard, the single-platform-owner
 * invariant, the every-client-has-one-default-branch invariant, and the public
 * path allowlist.
 *
 * Runs against the local docker-compose Postgres like every other integration
 * test here - see AuthIntegrationTest for why local Postgres over Testcontainers.
 * Requires `docker compose up -d` at the project root.
 *
 * The guard is exercised through a real HTTP round trip rather than by calling it
 * directly, because the thing worth proving is not "the method throws" but "the
 * caller gets a 403 with an ApiError body" - which depends on
 * MarketplaceAccessExceptionHandler being global, and would silently regress to a
 * 500 if it were ever scoped to one controller. The marketplace-admin controllers
 * themselves are built by other modules, so this test stands up a minimal probe
 * controller on the same path prefix.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
@Import(MarketplaceFoundationIntegrationTest.PlatformOwnerProbeConfiguration.class)
class MarketplaceFoundationIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final String PROBE_PATH = "/api/marketplace/admin/_platform-owner-probe";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private MarketplaceSettingsRepository marketplaceSettingsRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PlatformOwnerGuard platformOwnerGuard;

    @PersistenceContext
    private EntityManager entityManager;

    // ------------------------------------------------------------------------
    // (a) the guard
    // ------------------------------------------------------------------------

    @Test
    void anOrdinaryTenantsOwnerIsRefusedOnAMarketplaceAdminSurface() {
        // This user holds MANAGE_MARKETPLACE - every OWNER does, because
        // permissions hang off global roles - so @PreAuthorize alone would let them
        // straight through. The 403 can only come from the platform-owner check.
        TenantLoginResponse owner = signup("Not The Operator Co");
        assertThat(owner.user().permissions()).contains("MANAGE_MARKETPLACE");
        assertThat(owner.user().platformOwner()).isFalse();

        ResponseEntity<ApiError> response = restTemplate.exchange(
                PROBE_PATH, HttpMethod.GET, new HttpEntity<>(authHeaders(owner)), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("marketplace operator");
    }

    @Test
    void theSeededPlatformOwnerReachesTheSameSurface() {
        TenantLoginResponse operator = loginAsPlatformOwner();

        ResponseEntity<String> response = restTemplate.exchange(
                PROBE_PATH, HttpMethod.GET, new HttpEntity<>(authHeaders(operator)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("procurepal");
    }

    /** The claim, the login response and /api/me all have to agree - see JwtClaims.PLATFORM_OWNER. */
    @Test
    void platformOwnerIsExposedOnTheLoginResponseForTheOperator() {
        assertThat(loginAsPlatformOwner().user().platformOwner()).isTrue();
    }

    // ------------------------------------------------------------------------
    // (b) at most one platform owner
    // ------------------------------------------------------------------------

    @Test
    void asecondPlatformOwnerIsRejectedByTheDatabaseNotJustByServiceCode() {
        assertThat(clientRepository.countByPlatformOwnerTrue()).isEqualTo(1);

        String unique = UUID.randomUUID().toString();
        Client impostor = Client.builder()
                .name("Impostor Marketplace " + unique)
                .slug("impostor-" + unique)
                .adminContactEmail("impostor-" + unique + "@example.com")
                .active(true)
                .platformOwner(true)
                .build();

        // saveAndFlush, not save: the constraint has to be hit now rather than at an
        // unrelated flush later, which is the whole point of asserting the DB
        // enforces this rather than a service check.
        assertThatThrownBy(() -> clientRepository.saveAndFlush(impostor))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(clientRepository.countByPlatformOwnerTrue()).isEqualTo(1);
    }

    // ------------------------------------------------------------------------
    // (c) exactly one default branch per client
    // ------------------------------------------------------------------------

    @Test
    void signupCreatesExactlyOneDefaultHeadOfficeBranch() {
        TenantLoginResponse owner = signup("Branch On Signup Co");
        UUID clientId = clientRepository.findBySlug(owner.user().clientIdentifier()).orElseThrow().getId();

        List<Branch> branches = branchRepository.findAllByClientId(clientId);

        assertThat(branches).hasSize(1);
        assertThat(branches.getFirst().getName()).isEqualTo("Head Office");
        assertThat(branches.getFirst().isDefaultBranch()).isTrue();
        assertThat(branches.getFirst().isActive()).isTrue();
        assertThat(branchRepository.findByClientIdAndDefaultBranchTrue(clientId)).isPresent();
    }

    /**
     * The clients that predate branches: V6's backfill covers everything that
     * existed when it ran, and the seed's safety net covers the demo tenants, which
     * db/seed creates AFTER that backfill on a fresh database.
     *
     * Deliberately checks the seeded tenants by name rather than sweeping
     * clientRepository.findAll(): several test fixtures insert a client straight
     * through the repository, bypassing ClientSignupService entirely, and those
     * legitimately have no branch. Asserting over every row would be asserting
     * something the product never promised.
     */
    @Test
    void theSeededTenantsEachHaveExactlyOneDefaultBranch() {
        for (String slug : List.of("demo", "procurepal")) {
            Client client = clientRepository.findBySlug(slug).orElseThrow();
            assertThat(branchRepository.countByClientIdAndDefaultBranchTrue(client.getId()))
                    .as("default branches for seeded client %s", slug)
                    .isEqualTo(1);
        }
    }

    /** The partial unique index, checked where it matters: nobody can end up with two defaults. */
    @Test
    void noClientCanHaveMoreThanOneDefaultBranch() {
        List<Client> clients = clientRepository.findAll();
        assertThat(clients).isNotEmpty();

        assertThat(clients)
                .allSatisfy(client -> assertThat(branchRepository.countByClientIdAndDefaultBranchTrue(client.getId()))
                        .as("default branches for client %s", client.getSlug())
                        .isLessThanOrEqualTo(1));
    }

    // ------------------------------------------------------------------------
    // The cross-tenant read escape hatch the fulfilment queue is built on.
    // ------------------------------------------------------------------------

    /**
     * orders.client_id is the BUYER, so ProcurePal's fulfilment queue is the one
     * caller that has to read other tenants' rows. This pins down both halves of
     * that: the tenant filter really does hide them by default, and
     * readAcrossTenants is the only thing that reveals them - and puts the filter
     * back afterwards.
     *
     * The Hibernate filter is enabled by hand here exactly the way
     * TenantResolutionFilter does it for a real request; see
     * TenantIsolationIntegrationTest, which uses the same technique.
     */
    @Test
    @Transactional
    void thePlatformOwnerSeesAnotherTenantsOrderOnlyInsideReadAcrossTenants() {
        UUID buyerId = clientRepository.findBySlug("demo").orElseThrow().getId();
        UUID operatorId = clientRepository.findByPlatformOwnerTrue().orElseThrow().getId();

        TenantContext.set(buyerId);
        Order buyerOrder;
        try {
            buyerOrder = orderRepository.saveAndFlush(Order.builder()
                    .orderNumber("PP-GUARDTEST-" + UUID.randomUUID().toString().substring(0, 8))
                    // NOT NULL since V11: every order names its seller. ProcurePal
                    // here, which is what every order in this database means.
                    .sellerClientId(operatorId)
                    // NOT NULL since V12: every order names the checkout it came
                    // out of. A fixture order is its own checkout - a group of one -
                    // which is what V12's backfill made every pre-split row and what a
                    // single-seller basket still produces today.
                    .checkoutGroupId(UUID.randomUUID())
                    .status(OrderStatus.PLACED)
                    .paymentStatus(PaymentStatus.ON_DELIVERY)
                    .paymentMethod(PaymentMethod.PAY_ON_DELIVERY)
                    .currency("NGN")
                    .subtotal(new BigDecimal("92000.00"))
                    .deliveryFee(BigDecimal.ZERO)
                    .total(new BigDecimal("92000.00"))
                    .build());
        } finally {
            TenantContext.clear();
        }
        assertThat(buyerOrder.getClientId()).isEqualTo(buyerId);

        TenantContext.set(operatorId);
        entityManager.unwrap(Session.class)
                .enableFilter(TenantAwareEntity.TENANT_FILTER_NAME)
                .setParameter(TenantAwareEntity.TENANT_FILTER_PARAM, operatorId);
        try {
            assertThat(placedOrderIds()).doesNotContain(buyerOrder.getId());

            assertThat(platformOwnerGuard.readAcrossTenants(this::placedOrderIds)).contains(buyerOrder.getId());

            // Restored, not left off: the rest of the request must go back to being
            // tenant-filtered.
            assertThat(placedOrderIds()).doesNotContain(buyerOrder.getId());
        } finally {
            TenantContext.clear();
        }
    }

    private List<UUID> placedOrderIds() {
        return orderRepository
                .findAllByStatusOrderByCreatedAtDesc(OrderStatus.PLACED, PageRequest.of(0, 200))
                .stream()
                .map(Order::getId)
                .toList();
    }

    // ------------------------------------------------------------------------
    // Seed + settings sanity: other modules build on both.
    // ------------------------------------------------------------------------

    /** Transactional so the lazy category association can be navigated outside a web request. */
    @Test
    @Transactional(readOnly = true)
    void theMarketplaceIsSeededWithASingleSettingsRowAndAListedCatalog() {
        MarketplaceSettings settings = marketplaceSettingsRepository.findBySingletonTrue().orElseThrow();
        assertThat(marketplaceSettingsRepository.count()).isEqualTo(1);
        assertThat(settings.getDeliveryFee()).isNotNull();
        assertThat(settings.isPayOnDeliveryEnabled()).isTrue();

        UUID operatorId = clientRepository.findByPlatformOwnerTrue().orElseThrow().getId();
        assertThat(productRepository.countByClientIdAndMarketplaceListedTrue(operatorId)).isGreaterThanOrEqualTo(25);
        assertThat(productRepository
                        .findAllByClientIdAndMarketplaceListedTrueAndActiveTrue(operatorId, PageRequest.of(0, 5))
                        .getContent())
                .allSatisfy(product -> {
                    assertThat(product.getSlug()).isNotBlank();
                    assertThat(product.getUnitOfMeasure()).isNotBlank();
                    assertThat(product.getMinOrderQuantity()).isGreaterThanOrEqualTo(1);
                    assertThat(product.getCategory()).isNotNull();
                });
    }

    /**
     * No listed product may belong to anyone but the marketplace operator. There is
     * no CHECK constraint that can express this (it needs a join), so it is asserted
     * here as the backstop for the service-code rule.
     */
    @Test
    void nobodyButThePlatformOwnerHasAListedProduct() {
        UUID operatorId = clientRepository.findByPlatformOwnerTrue().orElseThrow().getId();

        assertThat(productRepository.findAll().stream()
                        .filter(product -> product.isMarketplaceListed())
                        .map(product -> product.getClientId())
                        .distinct())
                .containsExactly(operatorId);
    }

    // ------------------------------------------------------------------------
    // The public path allowlist.
    // ------------------------------------------------------------------------

    /**
     * Asserts "not 403" rather than a specific status: these routes are public from
     * now on, but the controllers behind them belong to other modules, so today they
     * 404 and tomorrow they 200. Either is fine; a 403 means the allowlist broke.
     */
    @Test
    void storefrontAndWebhookPathsAreReachableWithoutAToken() {
        assertNotForbidden(HttpMethod.GET, "/api/marketplace/catalog");
        assertNotForbidden(HttpMethod.GET, "/api/marketplace/catalog/some-product-slug");
        assertNotForbidden(HttpMethod.GET, "/api/marketplace/categories");
        assertNotForbidden(HttpMethod.GET, "/api/marketplace/settings");
        assertNotForbidden(HttpMethod.POST, "/api/payments/monnify/webhook");
    }

    /** The allowlist must not have opened anything else up by accident. */
    @Test
    void otherMarketplacePathsStillRequireAToken() {
        ResponseEntity<String> response =
                restTemplate.exchange(PROBE_PATH, HttpMethod.GET, HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private void assertNotForbidden(HttpMethod method, String path) {
        ResponseEntity<String> response = restTemplate.exchange(path, method, HttpEntity.EMPTY, String.class);
        assertThat(response.getStatusCode()).as("%s %s", method, path).isNotEqualTo(HttpStatus.FORBIDDEN);
    }

    private TenantLoginResponse loginAsPlatformOwner() {
        TenantLoginResponse response = restTemplate.postForObject(
                "/api/auth/login", new LoginRequest("procurepal", "admin", "Demo1234!"), TenantLoginResponse.class);
        assertThat(response)
                .as("the procurepal demo tenant must be seeded - see db/seed/V9001__seed_procurepal_marketplace.sql")
                .isNotNull();
        assertThat(response.tokens()).isNotNull();
        return response;
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

    @TestConfiguration
    static class PlatformOwnerProbeConfiguration {

        @Bean
        PlatformOwnerProbeController platformOwnerProbeController(PlatformOwnerGuard platformOwnerGuard) {
            return new PlatformOwnerProbeController(platformOwnerGuard);
        }
    }

    /**
     * A stand-in for the real marketplace-admin controllers other modules own, wired
     * exactly the way they are told to wire theirs: @PreAuthorize for the job,
     * PlatformOwnerGuard for the company. If this shape stops producing a 403 for an
     * ordinary tenant, theirs will stop too.
     */
    @RestController
    static class PlatformOwnerProbeController {

        private final PlatformOwnerGuard platformOwnerGuard;

        PlatformOwnerProbeController(PlatformOwnerGuard platformOwnerGuard) {
            this.platformOwnerGuard = platformOwnerGuard;
        }

        @GetMapping(PROBE_PATH)
        @PreAuthorize("hasAuthority('MANAGE_MARKETPLACE')")
        String probe() {
            return platformOwnerGuard.requirePlatformOwner().getSlug();
        }
    }
}
