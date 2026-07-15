package com.procurepal_services.stock_bridge_api.security;

import com.procurepal_services.stock_bridge_api.config.CorsProperties;
import com.procurepal_services.stock_bridge_api.tenant.TenantResolutionFilter;

import java.util.Arrays;
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
        "/api/superadmin/auth/login",
        "/api/superadmin/auth/refresh",
        "/actuator/health",
        "/actuator/health/**",
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
