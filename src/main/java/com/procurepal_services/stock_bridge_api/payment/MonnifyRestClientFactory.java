package com.procurepal_services.stock_bridge_api.payment;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Builds the {@link RestClient} {@link MonnifyRestClient} talks through.
 *
 * <p>RestClient rather than WebClient because the codebase is servlet-stack
 * throughout (spring-boot-starter-web, {@code open-in-view=true}, a ThreadLocal
 * TenantContext) and has no reactive dependency. Pulling WebFlux in for three
 * blocking calls would add a second HTTP stack to maintain and a reactive
 * boundary that TenantContext does not survive.
 *
 * <p>Timeouts are the point of this class. Without them a hung Monnify would pin
 * a Tomcat worker indefinitely, and enough stuck checkouts would take the whole
 * API down - a payment provider outage must degrade card payment, not the app.
 * The read timeout is generous because init-transaction is not fast; the
 * reconciliation sweep is what covers anything that times out anyway.
 *
 * <p>Base URL is bound at construction, so an unconfigured deployment produces a
 * client that is never called - {@link MonnifyRestClient} checks
 * {@code isConfigured()} before every request.
 */
final class MonnifyRestClientFactory {

    private MonnifyRestClientFactory() {
    }

    static RestClient build(MonnifyProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());

        return RestClient.builder()
                // Blank when unconfigured; harmless, because no request is ever issued
                // in that state.
                .baseUrl(properties.baseUrl() == null ? "" : properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
