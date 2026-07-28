package com.procurepal_services.stock_bridge_api.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.entity.PaymentTerms;
import org.junit.jupiter.api.Test;

/**
 * The password must not be printable. Spring logs a bound
 * {@code @ConfigurationProperties} object in binding-failure messages, actuator's
 * {@code /configprops} renders property state, and a record's generated
 * {@code toString()} prints every component - so "nobody currently logs this
 * object" is a fact about today's code, not a property of the design. These
 * tests pin the redaction so it survives someone adding a field later.
 */
class BootstrapPropertiesTest {

    private static final String SECRET = "correct-horse-battery-staple";

    @Test
    void superAdminPropertiesNeverPrintThePassword() {
        String rendered = new SuperAdminBootstrapProperties("admin", SECRET).toString();

        assertThat(rendered).doesNotContain(SECRET).contains("password=***").contains("admin");
    }

    @Test
    void platformOwnerPropertiesNeverPrintThePassword() {
        String rendered = new PlatformOwnerBootstrapProperties(
                        "ProcurePal",
                        "procurepal",
                        "ops@procurepal.example.com",
                        "admin",
                        SECRET,
                        "+234 800 000 0000",
                        PaymentTerms.PREPAID)
                .toString();

        assertThat(rendered)
                .doesNotContain(SECRET)
                .contains("adminPassword=***")
                // The non-secret identity stays visible: that is what makes a
                // logged properties object worth having at all.
                .contains("slug=procurepal")
                .contains("adminUsername=admin");
    }

    /**
     * The defaults live in the compact constructor as well as in
     * {@code application.yml}, so the record is safe to build directly. The two
     * gate fields are pointedly excluded - a default password here would
     * reintroduce, in Java, exactly the vulnerability removed from the YAML.
     */
    @Test
    void platformOwnerPropertiesDefaultEverythingExceptTheCredential() {
        PlatformOwnerBootstrapProperties properties =
                new PlatformOwnerBootstrapProperties(null, null, "ops@procurepal.example.com", null, null, "  ", null);

        assertThat(properties.name()).isEqualTo("ProcurePal");
        assertThat(properties.slug()).isEqualTo("procurepal");
        // Mirrors ClientSignupService: a tenant's first user logs in with the
        // admin email it signed up with, unless told otherwise.
        assertThat(properties.adminUsername()).isEqualTo("ops@procurepal.example.com");
        assertThat(properties.paymentTerms()).isEqualTo(PaymentTerms.PREPAID);
        // Blank is how a form (or an env-var editor) says "empty"; the database
        // should say NULL.
        assertThat(properties.phone()).isNull();
        assertThat(properties.adminPassword()).isNull();
    }
}
