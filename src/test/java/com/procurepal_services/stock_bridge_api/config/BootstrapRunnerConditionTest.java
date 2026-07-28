package com.procurepal_services.stock_bridge_api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

import com.procurepal_services.stock_bridge_api.repository.BranchRepository;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.RoleRepository;
import com.procurepal_services.stock_bridge_api.repository.SuperAdminRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The startup-cost contract for both bootstrap runners: when the environment
 * variables are absent the bean must not exist at all.
 *
 * <p>Asserting on bean presence rather than on behaviour is the point. "The
 * runner returns early when unconfigured" would pass just as happily with an
 * {@code if} at the top of {@code run()}, and that version still constructs the
 * bean, injects its repositories and invokes it on every single container start.
 * {@code doesNotHaveBean} is the only assertion that actually pins down "costs
 * nothing" - if no bean is created, no repository is ever handed to it and no
 * query can be issued.
 *
 * <p>Runs on a bare {@link ApplicationContextRunner} with mocked collaborators:
 * no database, no Spring Boot application, milliseconds. The mocks are also the
 * safety net for the claim above - {@link #noRepositoryIsEverTouchedWhenUnconfigured()}
 * checks they were never interacted with.
 */
class BootstrapRunnerConditionTest {

    private static final String EMAIL = "app.platform-owner.admin-email";
    private static final String PLATFORM_PASSWORD = "app.platform-owner.admin-password";
    private static final String USERNAME = "app.super-admin.username";
    private static final String SUPER_ADMIN_PASSWORD = "app.super-admin.password";

    private final ClientRepository clientRepository = mock(ClientRepository.class);
    private final SuperAdminRepository superAdminRepository = mock(SuperAdminRepository.class);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(ClientRepository.class, () -> clientRepository)
            .withBean(BranchRepository.class, () -> mock(BranchRepository.class))
            .withBean(UserRepository.class, () -> mock(UserRepository.class))
            .withBean(RoleRepository.class, () -> mock(RoleRepository.class))
            .withBean(SuperAdminRepository.class, () -> superAdminRepository)
            .withBean(TransactionTemplate.class, () -> mock(TransactionTemplate.class))
            .withBean(PasswordEncoder.class, BCryptPasswordEncoder::new)
            .withBean(PlatformOwnerBootstrapProperties.class, this::platformOwnerProperties)
            .withBean(SuperAdminBootstrapProperties.class, this::superAdminProperties)
            .withUserConfiguration(PlatformOwnerBootstrapRunner.class, SuperAdminBootstrapRunner.class);

    @Test
    void neitherRunnerExistsWhenNothingIsConfigured() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(PlatformOwnerBootstrapRunner.class);
            assertThat(context).doesNotHaveBean(SuperAdminBootstrapRunner.class);
        });
    }

    /**
     * The case that decided the shape of {@link ConditionalOnNonBlankProperties}.
     * Every one of these properties is declared in {@code application.yml} as
     * {@code ${SOME_ENV_VAR:}}, so an unset variable arrives as a present-but-empty
     * property rather than an absent one. Spring Boot's own
     * {@code @ConditionalOnProperty} would match here - its test is "present and
     * not literally 'false'" - and the bean would be created on every deployment
     * that had configured nothing. If this test ever starts failing because
     * someone swapped the annotation for the stock one, that is the regression.
     */
    @Test
    void neitherRunnerExistsWhenTheEnvironmentVariablesResolveToBlank() {
        contextRunner
                .withPropertyValues(EMAIL + "=", PLATFORM_PASSWORD + "=", USERNAME + "=", SUPER_ADMIN_PASSWORD + "=")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(PlatformOwnerBootstrapRunner.class);
                    assertThat(context).doesNotHaveBean(SuperAdminBootstrapRunner.class);
                });
    }

    /** Half-configured is treated exactly like unconfigured - both halves or nothing. */
    @Test
    void neitherRunnerExistsWhenOnlyTheNonSecretHalfIsSet() {
        contextRunner
                .withPropertyValues(EMAIL + "=ops@procurepal.example.com", USERNAME + "=admin")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(PlatformOwnerBootstrapRunner.class);
                    assertThat(context).doesNotHaveBean(SuperAdminBootstrapRunner.class);
                });
    }

    @Test
    void eachRunnerExistsOnlyOnceItsOwnPairIsSet() {
        contextRunner
                .withPropertyValues(
                        EMAIL + "=ops@procurepal.example.com", PLATFORM_PASSWORD + "=correct-horse-battery-staple")
                .run(context -> {
                    assertThat(context).hasSingleBean(PlatformOwnerBootstrapRunner.class);
                    // The two bootstraps are independent: configuring the
                    // marketplace must not conjure a platform login.
                    assertThat(context).doesNotHaveBean(SuperAdminBootstrapRunner.class);
                });

        contextRunner
                .withPropertyValues(USERNAME + "=admin", SUPER_ADMIN_PASSWORD + "=correct-horse-battery-staple")
                .run(context -> {
                    assertThat(context).hasSingleBean(SuperAdminBootstrapRunner.class);
                    assertThat(context).doesNotHaveBean(PlatformOwnerBootstrapRunner.class);
                });
    }

    /**
     * The actual requirement, stated directly: an unconfigured container start
     * issues no query. Bean absence implies it, but this asserts on the thing an
     * operator would measure.
     */
    @Test
    void noRepositoryIsEverTouchedWhenUnconfigured() {
        contextRunner.run(context -> {
            assertThat(mockingDetails(clientRepository).getInvocations()).isEmpty();
            assertThat(mockingDetails(superAdminRepository).getInvocations()).isEmpty();
        });
    }

    private PlatformOwnerBootstrapProperties platformOwnerProperties() {
        return new PlatformOwnerBootstrapProperties(null, null, null, null, null, null, null);
    }

    private SuperAdminBootstrapProperties superAdminProperties() {
        return new SuperAdminBootstrapProperties(null, null);
    }
}
