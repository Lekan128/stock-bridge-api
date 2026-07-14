package com.procurepal_services.stock_bridge_api.tenant;

import com.procurepal_services.stock_bridge_api.security.TenantPrincipal;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.hibernate.Session;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the current request's tenant from the authenticated principal
 * (populated by the JWT filter, added in a later step) into TenantContext,
 * and enables the Hibernate "tenantFilter" (see TenantAwareEntity) on the
 * request's persistence context so every query against a tenant-scoped
 * entity is transparently restricted to that tenant - this is layer 1 of
 * tenant isolation. If there's no authenticated principal (public endpoints)
 * or the principal isn't a TenantPrincipal (e.g. a future super-admin
 * principal), TenantContext is left empty and the filter is left disabled.
 *
 * Why this filter can enable the Hibernate filter at all: it relies on
 * spring.jpa.open-in-view=true, which binds a single Hibernate Session to the
 * request thread for the whole request (via Spring's
 * OpenEntityManagerInViewFilter, which Spring Boot registers to run before
 * this one). That's what makes "enable the filter once, here" apply to every
 * repository call made later in the request, not just calls made inside this
 * method. With open-in-view=false, each @Transactional boundary opens its own
 * Session and this call would have no effect on it - so a plain
 * OncePerRequestFilter (the simplest of the two approaches the task offered)
 * would silently do nothing. We accept OSIV's usual downsides (a DB
 * connection held for the whole request, N+1 queries not surfacing until the
 * view layer) in exchange for a hook point that's simple and hard to get
 * wrong for a security-critical concern. Layer 2 - TenantScopedRepository's
 * explicit client_id predicates - holds regardless of this tradeoff.
 *
 * Registered via HttpSecurity.addFilterAfter(..., UsernamePasswordAuthenticationFilter.class)
 * in SecurityConfig, so it runs after Spring Security's authentication
 * filters have populated (or not) the SecurityContext for the request.
 */
@Component
public class TenantResolutionFilter extends OncePerRequestFilter {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            UUID tenantId = resolveTenantId();
            if (tenantId != null) {
                TenantContext.set(tenantId);
                entityManager.unwrap(Session.class)
                        .enableFilter(TenantAwareEntity.TENANT_FILTER_NAME)
                        .setParameter(TenantAwareEntity.TENANT_FILTER_PARAM, tenantId);
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private UUID resolveTenantId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        if (authentication.getPrincipal() instanceof TenantPrincipal tenantPrincipal) {
            return tenantPrincipal.getClientId();
        }
        return null;
    }
}
