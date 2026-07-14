package com.procurepal_services.stock_bridge_api.config;

import com.procurepal_services.stock_bridge_api.entity.SuperAdmin;
import com.procurepal_services.stock_bridge_api.repository.SuperAdminRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first super admin from SUPERADMIN_USERNAME/SUPERADMIN_PASSWORD
 * on startup, if set and no super admin exists yet. Safe to leave in place
 * permanently: once a row exists, or if the env vars aren't set, this is a
 * no-op every subsequent boot.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SuperAdminBootstrapRunner implements ApplicationRunner {

    private final SuperAdminRepository superAdminRepository;
    private final PasswordEncoder passwordEncoder;
    private final SuperAdminBootstrapProperties properties;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.isConfigured()) {
            log.info("SUPERADMIN_USERNAME/SUPERADMIN_PASSWORD not set; skipping super admin bootstrap");
            return;
        }
        if (superAdminRepository.count() > 0) {
            log.info("A super admin already exists; skipping super admin bootstrap");
            return;
        }
        SuperAdmin superAdmin = SuperAdmin.builder()
                .username(properties.username())
                .passwordHash(passwordEncoder.encode(properties.password()))
                .build();
        superAdminRepository.save(superAdmin);
        log.info("Created super admin account '{}'", properties.username());
    }
}
