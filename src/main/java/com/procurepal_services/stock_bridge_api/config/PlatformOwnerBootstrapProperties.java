package com.procurepal_services.stock_bridge_api.config;

import com.procurepal_services.stock_bridge_api.entity.PaymentTerms;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The marketplace operator's own tenant, described by environment variables so a
 * fresh production database can be brought up with a working marketplace.
 *
 * <p>Until now the platform owner existed only in
 * {@code db/seed/V9001__seed_procurepal_marketplace.sql}, and {@code db/seed} is
 * on {@code spring.flyway.locations} in the {@code local} and {@code docker}
 * profiles only - never in {@code application-prod.yml}, deliberately, because
 * demo data must not reach a real database. The consequence was that a fresh
 * production deploy had no platform owner at all, and a marketplace with no
 * seller is not a marketplace. This is the production-safe equivalent, built the
 * same way as the super admin bootstrap rather than as a migration, for the same
 * reason: the credential belongs in the platform's secret store, not in a SQL
 * file in version control.
 *
 * <p><b>The two variables that decide whether anything happens</b> are
 * {@code PLATFORM_OWNER_ADMIN_EMAIL} and {@code PLATFORM_OWNER_ADMIN_PASSWORD} -
 * see the constants below and {@link PlatformOwnerBootstrapRunner}'s bean
 * condition. They are the gate because they are the two facts that have no
 * defensible default: {@code clients.admin_contact_email} is NOT NULL and
 * inventing an address would send real operational mail nowhere, and a default
 * password is the exact vulnerability this change was written to remove.
 * Everything else defaults, because a wrong-but-harmless display name is a
 * five-second fix in the UI whereas a wrong credential is an incident.
 *
 * @param name the tenant's display name, e.g. {@code ProcurePal}.
 * @param slug the client identifier its users type at login. Defaults to
 *     {@code procurepal} to match what {@code V9001} seeds locally, so the demo
 *     walkthrough in {@code APP_TOUR.md} and a real deployment agree.
 * @param adminEmail {@code clients.admin_contact_email}, and by default also the
 *     root user's username - mirroring {@code ClientSignupService}, where a
 *     tenant's first user logs in with the admin email it signed up with.
 * @param adminUsername overrides that default when the operator wants a short
 *     login name (the seeded demo uses {@code admin}).
 * @param phone optional; blank becomes NULL, same normalisation
 *     {@code ClientSignupService} applies, because blank is how a form says
 *     "empty" and the database should say NULL.
 * @param paymentTerms defaults to PREPAID. The platform owner never actually
 *     buys through the marketplace, so this is close to meaningless for it - but
 *     the column is NOT NULL and PREPAID is the same conservative default every
 *     other client gets, so there is no reason to make this row special.
 */
@ConfigurationProperties(prefix = "app.platform-owner")
public record PlatformOwnerBootstrapProperties(
        String name,
        String slug,
        String adminEmail,
        String adminUsername,
        String adminPassword,
        String phone,
        PaymentTerms paymentTerms) {

    static final String ADMIN_EMAIL_PROPERTY = "app.platform-owner.admin-email";
    static final String ADMIN_PASSWORD_PROPERTY = "app.platform-owner.admin-password";

    static final String DEFAULT_NAME = "ProcurePal";
    static final String DEFAULT_SLUG = "procurepal";

    /**
     * Defaults are applied here as well as in {@code application.yml} so the
     * record is safe to construct directly in a test without spelling out every
     * knob - the same reasoning as {@code MonnifyProperties}. The two gate
     * fields are pointedly absent from this list: giving them a fallback here
     * would reintroduce the "silently bootstraps with a compiled-in credential"
     * bug through the back door.
     */
    public PlatformOwnerBootstrapProperties {
        name = orDefault(name, DEFAULT_NAME);
        slug = orDefault(slug, DEFAULT_SLUG);
        // Trim the email before it is reused as the username default, so a
        // stray trailing space in the platform's env-var editor cannot produce
        // a login name nobody can type.
        adminEmail = blankToNull(adminEmail);
        adminUsername = orDefault(adminUsername, adminEmail);
        phone = blankToNull(phone);
        paymentTerms = paymentTerms != null ? paymentTerms : PaymentTerms.PREPAID;
    }

    /**
     * Redacts the password, for the reasons spelled out on
     * {@link SuperAdminBootstrapProperties#toString()} - a record prints every
     * component by default, and this object is reachable from Spring's binding
     * failure messages and from actuator's property endpoints. See that method
     * too for why the password is a {@code String} and not a scrubbed
     * {@code char[]}.
     */
    @Override
    public String toString() {
        return "PlatformOwnerBootstrapProperties[name=" + name
                + ", slug=" + slug
                + ", adminEmail=" + adminEmail
                + ", adminUsername=" + adminUsername
                + ", adminPassword=***"
                + ", phone=" + phone
                + ", paymentTerms=" + paymentTerms
                + "]";
    }

    private static String orDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
