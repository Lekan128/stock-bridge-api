package com.procurepal_services.stock_bridge_api.config;

import com.procurepal_services.stock_bridge_api.entity.SuperAdmin;
import com.procurepal_services.stock_bridge_api.repository.SuperAdminRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the first super admin from SUPERADMIN_USERNAME/SUPERADMIN_PASSWORD on
 * startup, if no super admin exists yet. Safe to leave in place permanently:
 * once a row exists it is a no-op every subsequent boot.
 *
 * <p>The "are we configured?" question is answered by the bean condition, before
 * this class is instantiated - see {@link ConditionalOnNonBlankProperties} for
 * why that is worth a custom annotation and why it beats an early return inside
 * {@code run()}. An unconfigured deployment therefore pays literally nothing
 * here: no bean, no injected repository, no query. That matters because this
 * runs on every container start, not once per release.
 *
 * <p>When it IS configured the cost is one {@code SELECT count(*) FROM
 * super_admins}. There is no cheaper honest answer - "has anyone ever
 * bootstrapped this deployment?" is a fact only the database holds - and the
 * table has one row per platform operator, so the count is a trivial scan of a
 * page or two. Adding a bespoke {@code existsBy...} finder would save nothing
 * measurable and would mean editing a repository this module does not own.
 */
@Component
@ConditionalOnNonBlankProperties({
    SuperAdminBootstrapProperties.USERNAME_PROPERTY,
    SuperAdminBootstrapProperties.PASSWORD_PROPERTY
})
@RequiredArgsConstructor
@Slf4j
public class SuperAdminBootstrapRunner implements ApplicationRunner {

    private final SuperAdminRepository superAdminRepository;
    private final PasswordEncoder passwordEncoder;
    private final SuperAdminBootstrapProperties properties;

    /**
     * Deliberately not {@code @Transactional}. This is a single insert, so
     * {@code saveAndFlush} already runs in its own transaction, and wrapping it
     * in an outer one would actively break the race handling below: once
     * Postgres rejects a statement the surrounding transaction is poisoned and
     * marked rollback-only, so catching the exception and returning normally
     * would just trade a {@code DataIntegrityViolationException} for an
     * {@code UnexpectedRollbackException} thrown at the transaction boundary,
     * outside any catch block, failing startup. See
     * {@link PlatformOwnerBootstrapRunner} for how the same problem is handled
     * where a transaction genuinely is required.
     */
    @Override
    public void run(ApplicationArguments args) {
        if (superAdminRepository.count() > 0) {
            log.info("A super admin already exists; skipping super admin bootstrap");
            return;
        }
        SuperAdmin superAdmin = SuperAdmin.builder()
                .username(properties.username())
                .passwordHash(passwordEncoder.encode(properties.password()))
                .build();
        try {
            // saveAndFlush, not save: the insert has to actually reach Postgres
            // here so the unique-username constraint can reject a loser of the
            // race below, rather than at some later, unrelated flush.
            superAdminRepository.saveAndFlush(superAdmin);
        } catch (DataIntegrityViolationException e) {
            // Two containers booting at once both saw an empty table. The unique
            // index on super_admins.username is the real backstop - the count
            // above is only an optimisation that keeps the common case quiet.
            // The loser has nothing to do and nothing went wrong, so this is INFO
            // rather than ERROR: an operator paging on ERROR should not be woken
            // by a healthy rolling deploy. The exception is not logged with its
            // stack trace either, because the failing INSERT's parameters can
            // appear in a driver-level cause and one of them is the password
            // hash.
            log.info("Another instance created the super admin first; skipping super admin bootstrap");
            return;
        }
        // Username only. The password never appears in a log line, an exception
        // message, or SuperAdminBootstrapProperties.toString().
        log.info("Created super admin account '{}'", properties.username());
    }
}
