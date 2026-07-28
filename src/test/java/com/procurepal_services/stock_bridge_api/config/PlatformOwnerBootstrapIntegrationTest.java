package com.procurepal_services.stock_bridge_api.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.entity.Branch;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.PaymentTerms;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.repository.BranchRepository;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import com.procurepal_services.stock_bridge_api.user.TenantRoles;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link PlatformOwnerBootstrapRunner} against the real schema, on the local
 * docker-compose Postgres like every other integration test here - see
 * AuthIntegrationTest for why local Postgres over Testcontainers. Requires
 * `docker compose up -d` at the project root.
 *
 * <p>The properties below are set at the class level, which means the runner is
 * a real bean and Spring Boot invokes it for real while this context starts -
 * {@code @SpringBootTest} does call {@code ApplicationRunner}s. That startup run
 * is itself the first assertion: the {@code local} profile loads {@code db/seed},
 * so a platform owner ({@code procurepal}) already exists, and
 * {@link #theAlreadySeededPlatformOwnerIsLeftUntouched()} checks the runner
 * noticed and changed nothing. A deliberately distinct slug is configured so
 * that "did nothing" and "did the right thing" cannot be confused.
 *
 * <p>The creation path is exercised in a rolled-back transaction rather than
 * against a clean database, because there is exactly one platform owner row
 * allowed per database ({@code uq_clients_single_platform_owner}) and the shared
 * local database already has it. See the test itself for why that is honest
 * rather than a cheat.
 */
@SpringBootTest(
        properties = {
            "app.platform-owner.name=Bootstrap Runner Test Marketplace",
            "app.platform-owner.slug=" + PlatformOwnerBootstrapIntegrationTest.SLUG,
            "app.platform-owner.admin-email=" + PlatformOwnerBootstrapIntegrationTest.ADMIN_EMAIL,
            "app.platform-owner.admin-username=" + PlatformOwnerBootstrapIntegrationTest.ADMIN_USERNAME,
            "app.platform-owner.admin-password=" + PlatformOwnerBootstrapIntegrationTest.ADMIN_PASSWORD,
            "app.platform-owner.phone=+234 800 111 2222"
        })
@ActiveProfiles("local")
class PlatformOwnerBootstrapIntegrationTest {

    static final String SLUG = "bootstrap-runner-test-marketplace";
    static final String ADMIN_EMAIL = "ops@bootstrap-runner-test.example.com";
    static final String ADMIN_USERNAME = "bootstrap-admin";
    static final String ADMIN_PASSWORD = "correct-horse-battery-staple";

    /** The slug db/seed/V9001 gives the real platform owner locally. */
    private static final String SEEDED_SLUG = "procurepal";

    @Autowired
    private PlatformOwnerBootstrapRunner runner;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // ------------------------------------------------------------------------
    // Configured, but a platform owner already exists
    // ------------------------------------------------------------------------

    /**
     * The runner already ran once, during context startup, with the properties
     * above pointing at a different tenant entirely. It must have left the seeded
     * one alone and created nothing - "there is already a platform owner" is the
     * answer, not "is this the platform owner I was told about". Reconciling a
     * mismatch would mean a startup task quietly renaming or re-pointing a live
     * marketplace, which is not a decision an env var should be able to make.
     */
    @Test
    void theAlreadySeededPlatformOwnerIsLeftUntouched() {
        assertThat(clientRepository.countByPlatformOwnerTrue()).isEqualTo(1);
        assertThat(clientRepository.findByPlatformOwnerTrue().orElseThrow().getSlug())
                .isEqualTo(SEEDED_SLUG);
        assertThat(clientRepository.findBySlug(SLUG)).isEmpty();
    }

    /**
     * Idempotence, checked the way it actually happens in production: the same
     * container image restarting. Every boot after the first must be a no-op, and
     * must not throw - a bootstrap that fails startup once its work is done is a
     * deployment that can never be restarted.
     */
    @Test
    void runningAgainOnASubsequentBootChangesNothing() {
        long clientsBefore = clientRepository.count();

        runner.run(null);
        runner.run(null);

        assertThat(clientRepository.count()).isEqualTo(clientsBefore);
        assertThat(clientRepository.countByPlatformOwnerTrue()).isEqualTo(1);
        assertThat(clientRepository.findByPlatformOwnerTrue().orElseThrow().getSlug())
                .isEqualTo(SEEDED_SLUG);
    }

    // ------------------------------------------------------------------------
    // Configured, and no platform owner exists yet - the production first-boot case
    // ------------------------------------------------------------------------

    /**
     * A fresh production database, simulated. The test is {@code @Transactional}
     * and therefore rolled back, which lets it clear the seeded platform-owner
     * flag first: the partial unique index allows exactly one TRUE row per
     * database, and the shared local database permanently has it. Rolling back is
     * what makes "pretend nothing has been seeded" possible without leaving the
     * next test - or the next developer's `docker compose up` - looking at a
     * database with a test marketplace in it.
     *
     * <p>What this does NOT weaken: the runner's own transaction joins this one
     * (PROPAGATION_REQUIRED), so every insert, every NOT NULL column, every
     * foreign key and both partial unique indexes are exercised by real Postgres.
     * Only the commit is skipped.
     */
    @Test
    @Transactional
    void createsTheTenantItsHeadOfficeBranchAndItsRootOwnerUser() {
        Client seeded = clientRepository.findByPlatformOwnerTrue().orElseThrow();
        seeded.setPlatformOwner(false);
        clientRepository.saveAndFlush(seeded);
        assertThat(clientRepository.countByPlatformOwnerTrue()).isZero();

        runner.run(null);

        Client created = clientRepository.findBySlug(SLUG).orElseThrow();
        assertThat(created.isPlatformOwner()).isTrue();
        assertThat(created.isActive()).isTrue();
        assertThat(created.getName()).isEqualTo("Bootstrap Runner Test Marketplace");
        assertThat(created.getAdminContactEmail()).isEqualTo(ADMIN_EMAIL);
        assertThat(created.getPhone()).isEqualTo("+234 800 111 2222");
        assertThat(created.getPaymentTerms()).isEqualTo(PaymentTerms.PREPAID);

        // The same invariant ClientSignupService and V6's backfill maintain for
        // every other tenant: exactly one default branch, named 'Head Office'.
        // The platform owner is an ordinary tenant with a flag set, so nothing
        // downstream should have to special-case it.
        List<Branch> branches = branchRepository.findAllByClientId(created.getId());
        assertThat(branches).hasSize(1);
        assertThat(branches.getFirst().getName()).isEqualTo("Head Office");
        assertThat(branches.getFirst().isDefaultBranch()).isTrue();
        assertThat(branches.getFirst().isActive()).isTrue();

        User rootUser = userRepository
                .findByClientIdAndUsername(created.getId(), ADMIN_USERNAME)
                .orElseThrow();
        assertThat(rootUser.isRoot()).isTrue();
        assertThat(rootUser.isActive()).isTrue();
        assertThat(rootUser.getRole().getName()).isEqualTo(TenantRoles.OWNER);
        assertThat(rootUser.getEmail()).isEqualTo(ADMIN_EMAIL);
        // Persisted as a bcrypt hash, never as the plaintext that was configured.
        assertThat(rootUser.getPasswordHash()).isNotEqualTo(ADMIN_PASSWORD).startsWith("$2");
        assertThat(passwordEncoder.matches(ADMIN_PASSWORD, rootUser.getPasswordHash()))
                .isTrue();

        // Branch and User both extend TenantAwareEntity, whose @PrePersist reads
        // TenantContext - proving client_id landed correctly is proving the
        // set/try/finally around the two inserts actually worked.
        assertThat(branches.getFirst().getClientId()).isEqualTo(created.getId());
        assertThat(rootUser.getClientId()).isEqualTo(created.getId());
    }

    /**
     * Running twice inside the same rolled-back transaction: the second call must
     * see its own work and stop, not attempt a second insert. This is the
     * single-instance half of idempotence; the two-instances-at-once half is
     * handled by {@code uq_clients_single_platform_owner} and the
     * DataIntegrityViolationException catch in the runner, which cannot be
     * exercised here because a poisoned transaction cannot be continued (see the
     * TransactionTemplate comment in the runner for why the boundary sits where
     * it does).
     */
    @Test
    @Transactional
    void asecondCallInTheSameBootDoesNotCreateASecondTenant() {
        Client seeded = clientRepository.findByPlatformOwnerTrue().orElseThrow();
        seeded.setPlatformOwner(false);
        clientRepository.saveAndFlush(seeded);

        runner.run(null);
        long clientsAfterFirstRun = clientRepository.count();
        runner.run(null);

        assertThat(clientRepository.count()).isEqualTo(clientsAfterFirstRun);
        assertThat(clientRepository.countByPlatformOwnerTrue()).isEqualTo(1);
        assertThat(userRepository.countByClientId(
                        clientRepository.findBySlug(SLUG).orElseThrow().getId()))
                .isEqualTo(1);
    }
}
