package com.procurepal_services.stock_bridge_api.tenant;

import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewFilter;

/**
 * Opens the request's one Hibernate Session BEFORE Spring Security runs, so the Session
 * {@link TenantResolutionFilter} enables the tenant filter on is the same Session every repository
 * call in the request then uses.
 *
 * <p>Spring Boot's own {@code spring.jpa.open-in-view} does not do this: it registers an
 * interceptor that runs inside the DispatcherServlet, after the security filter chain. Until
 * 2026-11 that meant TenantResolutionFilter enabled the filter on a throwaway Session, every
 * request ran with layer 1 off, and a plain {@code findAll()} returned every company's rows - only
 * the explicit client_id predicates were holding. {@code open-in-view} is therefore false in
 * application.yml; this filter replaces it, for every request, MVC or not.
 */
@Configuration
public class RequestSessionConfig {

    /** Ahead of the security filter chain, which sits at {@link SecurityFilterProperties#DEFAULT_FILTER_ORDER}. */
    static final int ORDER = SecurityFilterProperties.DEFAULT_FILTER_ORDER - 10;

    @Bean
    public FilterRegistrationBean<OpenEntityManagerInViewFilter> openEntityManagerInViewFilter() {
        FilterRegistrationBean<OpenEntityManagerInViewFilter> registration =
                new FilterRegistrationBean<>(new OpenEntityManagerInViewFilter());
        registration.setOrder(ORDER);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
