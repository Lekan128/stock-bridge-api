package com.procurepal_services.stock_bridge_api.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.core.support.AbstractRepositoryMetadata;
import org.springframework.data.repository.query.Param;

/**
 * Every query method on a repository of a tenant-scoped entity names its tenant: a client_id in
 * the method name, a client id parameter, or a client_id in its {@code @Query}. That explicit
 * predicate is layer 2 of tenant isolation, and the one that also holds where the Hibernate filter
 * cannot reach - native SQL, a background thread, a request whose filter was never turned on
 * (which, until 2026-11, was every request; see {@link RequestSessionConfig}).
 *
 * <p>A method that has a real reason to read across tenants goes in {@link #ALLOWED}, with the
 * reason. Adding to that list is a decision for review, not a way to make this test pass.
 */
class TenantScopedRepositoryRulesTest {

    private static final String REPOSITORY_PACKAGE = "com.procurepal_services.stock_bridge_api.repository";

    /** "Repository#method" -> why it may omit the tenant. */
    private static final Map<String, String> ALLOWED = Map.ofEntries(
            Map.entry("ExpectedDeliveryRepository#findByShareToken",
                    "public supplier link: the unguessable token is the credential (V75)"),
            Map.entry("ImportSessionRepository#findExpiredUncommitted",
                    "scheduled purge across every company, no request"),
            Map.entry("OrderRepository#countByOrderNumberStartingWith",
                    "order numbers are platform-wide (PP-2026-...)"),
            Map.entry("OrderRepository#existsByOrderNumber",
                    "order numbers are platform-wide"),
            Map.entry("OrderRepository#findByOrderNumber",
                    "order numbers are platform-wide; callers check ownership"),
            Map.entry("OrderRepository#findAllByCheckoutGroupIdOrderByOrderNumberAsc",
                    "group id read from an order already loaded with an ownership check"),
            Map.entry("OrderRepository#findAllByStatusAndCreatedAtBefore",
                    "scheduled expiry of unpaid orders, no request"),
            Map.entry("OrderRepository#findAllByStatusOrderByCreatedAtDesc",
                    "platform owner's queue, inside PlatformOwnerGuard.readAcrossTenants"),
            Map.entry("OrderRepository#findVendorOrdersAwaitingEscrowRelease",
                    "scheduled escrow release across sellers, no request"),
            Map.entry("PurchaseOrderEmailRepository#countToAddressSince",
                    "daily cap per supplier email address, across companies on purpose"),
            Map.entry("PurchaseOrderEmailRepository#findAllByExpectedDeliveryIdInOrderByCreatedAtDesc",
                    "ids of the company's own purchase orders, already loaded scoped"),
            Map.entry("PurchaseOrderEmailRepository#findFirstByExpectedDeliveryIdOrderByCreatedAtDesc",
                    "id of a purchase order already loaded scoped"),
            Map.entry("PurchaseOrderEmailRepository#markByProviderMessageId",
                    "email provider webhook, keyed by the provider's message id"),
            Map.entry("PurchaseOrderEmailRepository#switchOffEmailingForSupplierOf",
                    "email provider webhook (bounce), keyed by the provider's message id"),
            Map.entry("PurchaseOrderEmailRepository#unsendOrderOf",
                    "email provider webhook (bounce), keyed by the provider's message id"),
            Map.entry("StockMovementRepository#existsCountAfter",
                    "product id of a product already locked with findByIdAndClientIdForUpdate"),
            Map.entry("StockMovementRepository#existsCountAfterInArea",
                    "product id of a product already locked with findByIdAndClientIdForUpdate"),
            Map.entry("StockMovementRepository#netChangeAfter",
                    "product id of a product already locked with findByIdAndClientIdForUpdate"),
            Map.entry("StockMovementRepository#netChangeAfterInArea",
                    "product id of a product already locked with findByIdAndClientIdForUpdate"),
            Map.entry("StockMovementRepository#findFirstByProductIdOrderByCreatedAtDescIdDesc",
                    "product id of a product already locked with findByIdAndClientIdForUpdate"),
            Map.entry("StockMovementRepository#findAllByReversesMovementIdOrderByCreatedAtAsc",
                    "id of a stock-out whose product was already locked scoped"),
            Map.entry("UserRepository#countMatchingEmailAddress",
                    "an email address can belong to users in several companies (unsubscribe, bounces)"),
            Map.entry("UserRepository#countPromotionalOptOutsMatchingEmailAddress",
                    "an email address can belong to users in several companies"),
            Map.entry("UserRepository#countVerifiedMatchingEmailAddress",
                    "an email address can belong to users in several companies"));

    @Test
    void everyTenantRepositoryQueryNamesItsTenant() throws ClassNotFoundException {
        TreeSet<String> unscoped = new TreeSet<>();
        for (Class<?> repository : repositories()) {
            Class<?> domain = AbstractRepositoryMetadata.getMetadata(repository).getDomainType();
            if (!TenantAwareEntity.class.isAssignableFrom(domain)) {
                continue;
            }
            for (Method method : repository.getDeclaredMethods()) {
                if (method.isDefault() || Modifier.isStatic(method.getModifiers()) || method.isSynthetic()) {
                    continue;
                }
                String key = repository.getSimpleName() + "#" + method.getName();
                if (!namesTenant(method) && !ALLOWED.containsKey(key)) {
                    unscoped.add(key);
                }
            }
        }
        assertThat(unscoped)
                .as("Repository methods on tenant-scoped entities with no client_id. Scope them, or add them to ALLOWED with the reason")
                .isEmpty();
    }

    @Test
    void everyAllowedExceptionStillExists() throws ClassNotFoundException {
        List<String> present = new ArrayList<>();
        for (Class<?> repository : repositories()) {
            for (Method method : repository.getDeclaredMethods()) {
                present.add(repository.getSimpleName() + "#" + method.getName());
            }
        }
        assertThat(present).as("stale ALLOWED entries").containsAll(ALLOWED.keySet());
    }

    private static boolean namesTenant(Method method) {
        if (method.getName().contains("ClientId") || method.getName().endsWith("ForCurrentTenant")) {
            return true;
        }
        for (Parameter parameter : method.getParameters()) {
            Param param = parameter.getAnnotation(Param.class);
            if (param != null && param.value().toLowerCase().contains("clientid")) {
                return true;
            }
        }
        Query query = method.getAnnotation(Query.class);
        return query != null && (query.value().contains("clientId") || query.value().contains("client_id"));
    }

    private static List<Class<?>> repositories() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(
                    org.springframework.beans.factory.annotation.AnnotatedBeanDefinition definition) {
                return definition.getMetadata().isInterface();
            }
        };
        scanner.addIncludeFilter(new AssignableTypeFilter(Repository.class));
        List<Class<?>> found = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(REPOSITORY_PACKAGE)) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            if (!type.isAnnotationPresent(org.springframework.data.repository.NoRepositoryBean.class)) {
                found.add(type);
            }
        }
        assertThat(found).as("repositories found").hasSizeGreaterThan(10);
        return found;
    }
}
