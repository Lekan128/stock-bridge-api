package com.procurepal_services.stock_bridge_api.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.Role;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.RoleRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.UUID;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves tenant isolation end to end against the local docker-compose Postgres
 * (not Testcontainers - the schema is already migrated there via Flyway, and
 * this keeps the test fast with no new dependencies; worth revisiting with
 * Testcontainers later for a fully self-contained/CI-friendly run).
 * Requires `docker compose up -d` to be running at the project root.
 */
@SpringBootTest
@ActiveProfiles("local")
class TenantIsolationIntegrationTest {

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRepository userRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    @Transactional
    void tenantScopedQueryNeverReturnsAnotherTenantsData() {
        Role storekeeperRole = roleRepository.findByName("STOREKEEPER")
                .orElseThrow(
                        () -> new IllegalStateException("STOREKEEPER role not seeded - run the Flyway migrations"));

        Client clientA = createClient("Client A");
        Client clientB = createClient("Client B");

        User userA = createUser(clientA.getId(), "alice", storekeeperRole);
        User userB = createUser(clientB.getId(), "bob", storekeeperRole);

        // Layer 1: enable the Hibernate filter for client A only, the same way
        // TenantResolutionFilter does for a real request.
        TenantContext.set(clientA.getId());
        entityManager.unwrap(Session.class)
                .enableFilter(TenantAwareEntity.TENANT_FILTER_NAME)
                .setParameter(TenantAwareEntity.TENANT_FILTER_PARAM, clientA.getId());

        // No explicit WHERE client_id in this call - findAll() is the plain
        // inherited JpaRepository method. The filter alone must supply it.
        List<User> visibleToClientA = userRepository.findAll();

        assertThat(visibleToClientA)
                .extracting(User::getId)
                .containsExactly(userA.getId())
                .doesNotContain(userB.getId());

        // Layer 2: even with the Hibernate filter turned off, the explicit
        // client_id predicate in TenantScopedRepository must not leak client B's
        // user when queried under client A's id.
        entityManager.unwrap(Session.class).disableFilter(TenantAwareEntity.TENANT_FILTER_NAME);
        assertThat(userRepository.findByIdAndClientId(userB.getId(), clientA.getId())).isEmpty();
        assertThat(userRepository.findByIdAndClientId(userA.getId(), clientA.getId())).isPresent();
    }

    private Client createClient(String name) {
        String slug = name.toLowerCase().replace(" ", "-") + "-" + UUID.randomUUID();
        Client client = Client.builder()
                .name(name)
                .slug(slug)
                .adminContactEmail(slug + "@example.com")
                .active(true)
                .build();
        return clientRepository.save(client);
    }

    private User createUser(UUID clientId, String username, Role role) {
        TenantContext.set(clientId);
        try {
            User user = User.builder()
                    .username(username)
                    .passwordHash("irrelevant-for-this-test")
                    .role(role)
                    .active(true)
                    .build();
            return userRepository.save(user);
        } finally {
            TenantContext.clear();
        }
    }
}
