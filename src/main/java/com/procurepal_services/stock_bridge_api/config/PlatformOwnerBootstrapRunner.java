package com.procurepal_services.stock_bridge_api.config;

import com.procurepal_services.stock_bridge_api.entity.Branch;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.Role;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.repository.BranchRepository;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.RoleRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import com.procurepal_services.stock_bridge_api.user.TenantRoles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates the platform-owner tenant - ProcurePal itself, the one client with
 * {@code clients.is_platform_owner = TRUE} that sells to every other tenant -
 * from environment variables on startup, if it does not exist yet.
 *
 * <p>Why this exists at all: the platform owner was previously created only by
 * {@code db/seed/V9001__seed_procurepal_marketplace.sql}, and {@code db/seed} is
 * on {@code spring.flyway.locations} only in the {@code local} and {@code docker}
 * profiles. A fresh production database therefore had no platform owner, no
 * public catalog and no seller - the marketplace was inert and there was no
 * supported way to fix it short of hand-written SQL against production. See
 * {@link PlatformOwnerBootstrapProperties} for why this is a bootstrap runner
 * rather than a migration.
 *
 * <p>It creates the same three rows, in the same order and the same transaction,
 * that {@code ClientSignupService} creates for an ordinary self-service tenant:
 * the client, its single default {@code Head Office} branch, and its first user
 * flagged {@code is_root} with the {@code OWNER} role. Deliberately so - the
 * platform owner is an ordinary tenant with a flag set, and every invariant the
 * rest of the system relies on ("every client has exactly one default branch",
 * "every client has exactly one root user") has to hold for it too.
 *
 * <p>Cost on a boot where nothing needs doing: if the environment variables are
 * absent this bean does not exist (see {@link ConditionalOnNonBlankProperties}),
 * so the cost is zero. If they are present, it is one
 * {@code SELECT count(*) FROM clients WHERE is_platform_owner} - answered
 * straight out of the partial unique index {@code uq_clients_single_platform_owner},
 * which indexes only the single TRUE row and so is one or two pages regardless of
 * how many tenants exist. That query is not worth designing around; what was
 * worth designing around is the bean, the injected repositories and the runner
 * invocation, and those are gone.
 */
@Component
@ConditionalOnNonBlankProperties({
    PlatformOwnerBootstrapProperties.ADMIN_EMAIL_PROPERTY,
    PlatformOwnerBootstrapProperties.ADMIN_PASSWORD_PROPERTY
})
@RequiredArgsConstructor
@Slf4j
public class PlatformOwnerBootstrapRunner implements ApplicationRunner {

    /**
     * The one branch every client starts with. Same literal as
     * {@code ClientSignupService.DEFAULT_BRANCH_NAME} and as V6's backfill - not
     * imported from there because that constant is private to the signup flow and
     * widening its visibility for a startup task would couple two things that
     * only happen to agree.
     */
    private static final String DEFAULT_BRANCH_NAME = "Head Office";

    private final ClientRepository clientRepository;
    private final BranchRepository branchRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final PlatformOwnerBootstrapProperties properties;

    /**
     * A {@link TransactionTemplate} rather than {@code @Transactional} on
     * {@code run()}, because the transaction boundary has to sit <em>inside</em>
     * the catch block, not outside it. The three inserts must be atomic (a client
     * with no branch or no owner would violate invariants the rest of the app
     * assumes), but Postgres poisons a transaction the moment a statement
     * violates a constraint: with {@code @Transactional} on {@code run()} we
     * would catch the {@link DataIntegrityViolationException}, return normally,
     * and then have the proxy throw {@code UnexpectedRollbackException} at the
     * method boundary where nothing can catch it - failing startup on what is
     * supposed to be the benign case. The template rolls back and rethrows the
     * original exception, which the code below can then handle.
     */
    private final TransactionTemplate transactionTemplate;

    @Override
    public void run(ApplicationArguments args) {
        if (clientRepository.countByPlatformOwnerTrue() > 0) {
            log.info("A platform owner tenant already exists; skipping platform owner bootstrap");
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(status -> createPlatformOwner());
        } catch (DataIntegrityViolationException e) {
            // Two containers booted at once and both saw an empty result above.
            // uq_clients_single_platform_owner (and the unique index on
            // clients.slug) is the real guarantee that only one wins; the count
            // is only an optimisation that keeps the ordinary boot quiet. The
            // loser has nothing left to do and nothing is wrong, so this is INFO,
            // not ERROR - a rolling deploy must not page anybody. The exception is
            // logged without its stack trace on purpose: a driver-level cause can
            // include the failing statement's bind parameters, and one of those is
            // a password hash.
            log.info("Another instance created the platform owner tenant first; skipping platform owner bootstrap");
            return;
        }
        // Identity only, never the credential - see
        // PlatformOwnerBootstrapProperties.toString() for the rest of the
        // password-handling rationale.
        log.info(
                "Created platform owner tenant '{}' (slug={}) with root user '{}'",
                properties.name(),
                properties.slug(),
                properties.adminUsername());
    }

    private void createPlatformOwner() {
        Role ownerRole = roleRepository
                .findByName(TenantRoles.OWNER)
                .orElseThrow(() -> new IllegalStateException("OWNER role not seeded - run the Flyway migrations"));

        Client client = clientRepository.saveAndFlush(Client.builder()
                .name(properties.name())
                .slug(properties.slug())
                .adminContactEmail(properties.adminEmail())
                .phone(properties.phone())
                .paymentTerms(properties.paymentTerms())
                // The whole point of this runner: nothing else in production can
                // set this flag. It is not exposed on signup (a self-service
                // tenant must not be able to declare itself the marketplace
                // operator) and it is not settable through super-admin client
                // management either.
                .platformOwner(true)
                .active(true)
                .build());

        // Branch and User extend TenantAwareEntity, whose @PrePersist refuses to
        // persist a row when TenantContext is empty - and startup has no request,
        // no authenticated principal and therefore no tenant context of its own.
        // This is exactly the "privileged, server-side-only flow" the User class
        // doc calls out, and it is the same set/try/finally ClientSignupService
        // uses for the identical situation. The finally is not optional: this
        // runs on the main startup thread, which goes on to serve nothing but
        // would still carry a stale ThreadLocal, and ApplicationRunner failures
        // must not leave one behind either.
        TenantContext.set(client.getId());
        try {
            branchRepository.save(Branch.builder()
                    .name(DEFAULT_BRANCH_NAME)
                    .defaultBranch(true)
                    .active(true)
                    .build());

            userRepository.save(User.builder()
                    .username(properties.adminUsername())
                    .passwordHash(passwordEncoder.encode(properties.adminPassword()))
                    .role(ownerRole)
                    .active(true)
                    // Root for the same reason signup's first user is: this is the
                    // account holder, and root is what stops another admin from
                    // locking them out or taking the account over.
                    .root(true)
                    .email(properties.adminEmail())
                    .build());
        } finally {
            TenantContext.clear();
        }
    }
}
