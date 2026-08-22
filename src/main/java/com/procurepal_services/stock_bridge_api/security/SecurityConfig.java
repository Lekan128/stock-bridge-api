package com.procurepal_services.stock_bridge_api.security;

import com.procurepal_services.stock_bridge_api.config.CorsProperties;
import com.procurepal_services.stock_bridge_api.tenant.TenantResolutionFilter;

import jakarta.servlet.DispatcherType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless JWT-based API security. Two filters are wired into the chain in
 * addition to Spring Security's own:
 * - JwtAuthenticationFilter, before UsernamePasswordAuthenticationFilter,
 *   which authenticates the request from the Authorization header.
 * - TenantResolutionFilter, after it, which reads the principal that filter
 *   set and resolves TenantContext/the Hibernate tenant filter from it.
 *
 * @EnableMethodSecurity turns on @PreAuthorize; permission-code claims are
 * exposed as authorities by JwtAuthenticationFilter, so e.g.
 * @PreAuthorize("hasAuthority('MANAGE_USERS')") works directly against them.
 * No endpoints use it yet - that starts in the user management step.
 */
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private static final String[] PERMIT_ALL_PATHS = {
        "/api/auth/login",
        "/api/auth/refresh",
        "/api/clients/signup",
        // The public "apply to sell on ProcurePaddy" form. Unauthenticated by
        // necessity: an applicant has no account and, if we reject them, never
        // will - a login wall in front of it would mean asking businesses to sign
        // up as a buyer in order to ask to be a seller.
        //
        // Same HAZARD as every path in this list: no principal, so TenantContext
        // is empty and the Hibernate tenant filter is DISABLED for the whole
        // request. The handler behind it does not depend on that filter and must
        // not start to - vendor_waitlist_applications is deliberately not
        // tenant-scoped (an applicant has no clients row to be scoped to), so
        // there is nothing for the filter to do. See VendorWaitlistService.
        //
        // It also creates a row and sends two emails, one to a caller-chosen
        // address, which is the abuse shape POST /api/me/email-verification
        // already has. It is rate limited the same way, by the same mechanism -
        // see VendorWaitlistRateLimiter.
        "/api/vendor-waitlist",
        "/api/superadmin/auth/login",
        "/api/superadmin/auth/refresh",
        "/actuator/health",
        "/actuator/health/**",
        // ProcurePal storefront, browsable before signing in - a wholesale price
        // list is the shop window, not a privilege.
        //
        // HAZARD: these run with NO authenticated principal, so
        // TenantResolutionFilter leaves TenantContext empty and the Hibernate
        // tenant filter (layer 1 of tenant isolation) DISABLED for the whole
        // request. A query that would normally be scoped for free is not scoped at
        // all here. Every handler behind these paths must therefore filter
        // explicitly: on is_marketplace_listed = true AND is_active = true AND
        // approval_status = 'APPROVED', and on client_id being one of the ACTIVE
        // SELLERS (SellerDirectory.activeSellerIds() - the platform owner plus
        // active vendors). Do not add a path here whose handler relies on the
        // tenant filter.
        //
        // The seller pin widened from one id to a set when selling opened up to
        // vendors; it did not go away, and it must not. Every buying company on
        // the platform keeps its private inventory in the same products table, so
        // an unpinned query here publishes all of it.
        "/api/marketplace/catalog/**",
        "/api/marketplace/categories",
        // The seller directory and per-vendor storefront headers. Same hazard, and
        // one specific to them: they read `clients`, which holds every buying
        // company too, so the handler must return active SELLERS only - never a
        // filtered view of the whole tenant list. See SellerNotFoundException.
        "/api/marketplace/sellers/**",
        "/api/marketplace/settings",
        // Same hazard, plus one more: this is called by Monnify, not by a browser,
        // so there is no token to authenticate and CSRF is disabled app-wide. Its
        // only authentication is the provider signature, which the handler MUST
        // verify before acting on the payload.
        "/api/payments/monnify/webhook",
        // ====================================================================
        // Email eligibility (see the email package). All three are registered
        // here ahead of the controllers that will serve them, because three
        // separate pieces of work each needed a line in this array and editing
        // one shared file three ways is how merges go wrong. Until a controller
        // exists a permitted path with no handler simply 404s, which is exactly
        // what an unimplemented route should do and is harmless in the meantime.
        //
        // All three carry the same HAZARD as the storefront paths above: no
        // authenticated principal, so TenantContext is empty and the Hibernate
        // tenant filter is DISABLED for the whole request. Every handler behind
        // them must scope its own queries explicitly and must treat its request
        // body as hostile - the token or signature in the payload is the only
        // authentication any of them will ever have.
        //
        // CSRF: disabled application-wide (see the filter chain below), so none
        // of these needs an exemption and none should add one. That is a
        // deliberate property of a stateless bearer-token API with no cookies -
        // there is no ambient credential for a cross-site POST to borrow - and
        // it is what already lets the Monnify webhook above work at all.
        //
        // Confirms a token emailed to an address, flipping users.is_email_
        // verified. Unauthenticated by necessity: the whole point is that the
        // recipient may not have an account they can sign into yet, and the
        // token in the body is the credential.
        "/api/email/verify",
        // RFC 8058 one-click unsubscribe. Unauthenticated by necessity in a
        // stronger sense than the others: the caller is the MAIL CLIENT, POSTing
        // the List-Unsubscribe URL on the reader's behalf with no browser
        // session, no token of ours and no user interaction. It must succeed on
        // the first POST or the mail provider treats the unsubscribe as broken,
        // so it can never redirect to a login.
        "/api/email/unsubscribe",
        // AWS SNS delivering SES bounce and complaint notifications, which flip
        // the same two flags off. Same shape as the Monnify webhook above: no
        // token to authenticate, so the handler MUST verify the SNS message
        // signature before acting on anything in the payload, and must handle
        // SubscriptionConfirmation as well as Notification.
        "/api/webhooks/ses/notifications",
        // ====================================================================
        // springdoc-openapi: browsable API docs, not a tenant/superadmin resource.
        "/v3/api-docs",
        "/v3/api-docs/**",
        "/swagger-ui.html",
        "/swagger-ui/**"
    };

    private final TenantResolutionFilter tenantResolutionFilter;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CorsProperties corsProperties;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Spring Security filters every dispatcher type, so when the
                        // container forwards a 404/400/500 to /error that forward is
                        // authorized again - and /error belongs to nobody, so an
                        // anonymous caller got 403 instead of the status the app
                        // actually chose. That silently broke the public storefront:
                        // an unknown product slug under the permitted
                        // /api/marketplace/catalog/** would answer 403 rather than
                        // 404. The error dispatch only renders a decision already
                        // made, so re-authorizing it protects nothing. Must be first,
                        // or the /api/** rules below match the forwarded URI first.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(PERMIT_ALL_PATHS).permitAll()
                        // Order matters: the superadmin rule must be checked before the
                        // general /api/** rule, or it would never be reached.
                        .requestMatchers("/api/superadmin/**")
                        .hasAuthority(JwtAuthenticationFilter.AUDIENCE_SUPERADMIN_AUTHORITY)
                        .requestMatchers("/api/**")
                        .hasAuthority(JwtAuthenticationFilter.AUDIENCE_TENANT_AUTHORITY)
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(tenantResolutionFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsProperties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        // The frontend sends bearer tokens via the Authorization header and keeps
        // them in memory/localStorage rather than cookies, so there's no
        // cross-site cookie to protect and no reason to allow credentialed CORS
        // requests. Only flip this to true if a future flow starts relying on
        // cookies (e.g. an httpOnly refresh-token cookie).
        configuration.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // "/api/superadmin/**" is a prefix of "/api/**", so this one registration
        // already applies the same CORS config to both consistently.
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
