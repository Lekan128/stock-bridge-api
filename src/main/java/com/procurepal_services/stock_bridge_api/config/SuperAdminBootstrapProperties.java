package com.procurepal_services.stock_bridge_api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SUPERADMIN_USERNAME / SUPERADMIN_PASSWORD. Both are optional and neither has a
 * default in {@code application.yml} any more: an unset pair resolves to blank,
 * {@link SuperAdminBootstrapRunner}'s bean condition therefore does not match,
 * and no super admin is created. That is the fail-safe direction. The previous
 * arrangement shipped {@code admin} / a hardcoded password as defaults in
 * {@code application.yml}, which applies to every profile including {@code prod},
 * so any production deploy that forgot to set them silently got a super admin
 * with credentials that are public in this repository. Convenience defaults now
 * live only in {@code application-local.yml} / {@code application-docker.yml},
 * where they cannot reach production.
 *
 * <p>There is no {@code isConfigured()} here on purpose. "Both must be non-blank"
 * is expressed once, as the bean condition on the runner, using the two constants
 * below; a second copy of the rule as an instance method is the kind of thing
 * that drifts.
 */
@ConfigurationProperties(prefix = "app.super-admin")
public record SuperAdminBootstrapProperties(String username, String password) {

    static final String USERNAME_PROPERTY = "app.super-admin.username";
    static final String PASSWORD_PROPERTY = "app.super-admin.password";

    /**
     * Redacts the password. A record's generated {@code toString()} prints every
     * component, and this object is genuinely reachable by things that print:
     * Spring logs the bound {@code @ConfigurationProperties} instance in binding
     * failure messages, actuator's {@code /env} and {@code /configprops}
     * endpoints render property state, and any future {@code log.debug("{}",
     * properties)} would be a one-character mistake with a plaintext credential
     * as its consequence. Overriding it here means the leak cannot happen by
     * accident anywhere.
     *
     * <p>Deliberately NOT storing the password as a {@code char[]} that could be
     * zeroed after use. That defence is meaningful when a secret is read from a
     * keystore into a buffer you control end to end. Here Spring's binder hands
     * us a {@code String} that it built from the environment variable, the
     * {@code Environment}'s property source keeps its own reference to the same
     * value for the whole life of the JVM, and the process environment holds the
     * original regardless. Scrubbing our copy would clear one of several
     * identical strings and buy nothing but the appearance of rigour, so the real
     * mitigations are the ones below: never log it, never put it in an exception
     * message, and redact it in {@code toString()}.
     */
    @Override
    public String toString() {
        return "SuperAdminBootstrapProperties[username=" + username + ", password=***]";
    }
}
