package com.procurepal_services.stock_bridge_api.tenant;

import com.procurepal_services.stock_bridge_api.security.SecurityConfig;
import com.procurepal_services.stock_bridge_api.security.TenantPrincipal;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;
import org.hibernate.Session;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
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
 * Why this filter can enable the Hibernate filter at all: it relies on the request's single
 * Hibernate Session already being open when it runs, bound to the request thread for the whole
 * request. {@link RequestSessionConfig} makes that so, by registering an
 * OpenEntityManagerInViewFilter ahead of the Spring Security filter chain this filter sits in.
 * Spring Boot's own spring.jpa.open-in-view does NOT: its interceptor opens the Session inside the
 * DispatcherServlet, after every security filter, so enabling the filter here would land on a
 * throwaway Session and silently do nothing - which is exactly how every request ran until
 * 2026-11 (TenantFilterLiveRequestIntegrationTest now guards it). We accept the usual
 * open-session-in-view downsides (a DB connection held for the whole request, N+1 queries not
 * surfacing until serialization) in exchange for a hook point that's simple and hard to get wrong
 * for a security-critical concern. Layer 2 - explicit client_id predicates, which
 * TenantScopedRepositoryRulesTest requires of every tenant repository method - holds regardless.
 *
 * Registered via HttpSecurity.addFilterAfter(..., UsernamePasswordAuthenticationFilter.class)
 * in SecurityConfig, so it runs after Spring Security's authentication
 * filters have populated (or not) the SecurityContext for the request.
 */
@Component
public class TenantResolutionFilter extends OncePerRequestFilter {

    /**
     * Requests that never get the Hibernate filter, whoever is signed in. TenantContext is still
     * set for them when the caller has a company token, exactly as before; only layer 1 is left off.
     *
     * <ul>
     *   <li>The public paths ({@link SecurityConfig#permitAllPaths()}). Their handlers serve
     *       anonymous visitors, so each already scopes every query itself (listed, active,
     *       approved, from an active seller), and a signed-in buyer browsing the storefront must
     *       see the same catalogue: with the filter on, every seller's product would be cut down
     *       to the buyer's own, and the shop would come back empty.</li>
     *   <li>ProcurePal's own admin screens ({@code /api/marketplace/admin/**}). They read across
     *       companies on purpose, behind PlatformOwnerGuard, with explicit predicates, and ran
     *       without the filter until 2026-11; they keep doing so.</li>
     *   <li>Super admin ({@code /api/superadmin/**}). A super admin token is not a company token,
     *       so the filter is never enabled there anyway; named so that stays true.</li>
     * </ul>
     */
    private static final RequestMatcher UNFILTERED = new OrRequestMatcher(Stream.concat(
                    Arrays.stream(SecurityConfig.permitAllPaths()),
                    Stream.of("/api/marketplace/admin/**", "/api/superadmin/**"))
            .map(pattern -> (RequestMatcher) PathPatternRequestMatcher.withDefaults().matcher(pattern))
            .toList());

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * The off switch for layer 1 ({@code TENANT_HIBERNATE_FILTER=false}): every request then runs
     * the way they all did before 2026-11, held by the explicit client_id predicates alone. For a
     * production surprise, not for everyday use - turning it off loses the safety net, not any
     * data, and needs only a restart.
     */
    @Value("${app.tenancy.hibernate-filter:true}")
    private boolean hibernateFilterEnabled;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            UUID tenantId = resolveTenantId();
            if (tenantId != null) {
                TenantContext.set(tenantId);
                if (hibernateFilterEnabled && !UNFILTERED.matches(request)) {
                    entityManager.unwrap(Session.class)
                            .enableFilter(TenantAwareEntity.TENANT_FILTER_NAME)
                            .setParameter(TenantAwareEntity.TENANT_FILTER_PARAM, tenantId);
                }
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
