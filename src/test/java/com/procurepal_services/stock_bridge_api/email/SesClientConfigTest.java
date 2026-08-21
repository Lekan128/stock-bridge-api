package com.procurepal_services.stock_bridge_api.email;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.storage.AwsProperties;
import com.procurepal_services.stock_bridge_api.storage.S3ClientConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;

/**
 * The SES counterpart to {@link com.procurepal_services.stock_bridge_api.storage.S3ClientConfigTest},
 * and it loads S3ClientConfig alongside on purpose: the interesting question is not
 * "does SES get a provider" but "do the two coexist", since as of the separate SES
 * role there are two beans of type {@link AwsCredentialsProvider} in the real context
 * and an ambiguity there would break startup for the whole application.
 *
 * <p>Nothing here talks to AWS. Building a client and building a credentials provider
 * are both pure object construction - which is precisely the property that lets the
 * whole configuration exist on a deploy with no AWS account at all.
 */
class SesClientConfigTest {

    private static final String SES_ROLE = "arn:aws:iam::123456789012:role/procurepal-ses-prod";
    private static final String S3_ROLE = "arn:aws:iam::123456789012:role/procurepal-s3-prod";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class, S3ClientConfig.class, SesClientConfig.class)
            .withPropertyValues(
                    "app.aws.region=us-east-1",
                    "app.aws.s3.bucket-name=test-bucket",
                    "app.aws.access-key-id=AKIAEXAMPLE",
                    "app.aws.secret-access-key=secret",
                    "app.email.enabled=true",
                    "app.email.from-address=no-reply@procurepal.test");

    /** Stands in for the application's @ConfigurationPropertiesScan, which a context runner has none of. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({AwsProperties.class, EmailProperties.class, SesRoleProperties.class})
    static class PropertiesConfig {
    }

    /**
     * The default, and the compatibility guarantee: a deployment that never sets the
     * new property must behave exactly as it did before the property existed.
     */
    @Test
    void withoutASesRoleArnSesSharesWhateverCredentialsS3Uses() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean("sesCredentialsProvider"))
                    .isSameAs(context.getBean("s3CredentialsProvider"));
            assertThat(context.getBean("sesCredentialsProvider")).isInstanceOf(StaticCredentialsProvider.class);
            // No SES role to assume means nothing to build an STS client for.
            assertThat(context).doesNotHaveBean(StsClient.class);
        });
    }

    @Test
    void withASesRoleArnSesAssumesItsOwnRole() {
        contextRunner.withPropertyValues("app.email.role.arn=" + SES_ROLE).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean("sesCredentialsProvider"))
                    .isInstanceOf(StsAssumeRoleCredentialsProvider.class);
            // Must be a bean rather than a private field of the provider above:
            // StsCredentialsProvider.close() closes only its session cache and leaves
            // the StsClient open, so the context has to be what shuts it down.
            assertThat(context).hasSingleBean(StsClient.class);
        });
    }

    /**
     * The whole point of the feature: SES and S3 end up on <em>different</em> roles,
     * from one set of user keys. Asserting they are distinct objects is what would
     * catch a future refactor that "simplifies" SES back onto the shared provider.
     */
    @Test
    void sesAndS3CanAssumeTwoDifferentRolesFromTheSameUserKeys() {
        contextRunner
                .withPropertyValues("app.aws.role-arn=" + S3_ROLE, "app.email.role.arn=" + SES_ROLE)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AwsCredentialsProvider ses = (AwsCredentialsProvider) context.getBean("sesCredentialsProvider");
                    AwsCredentialsProvider s3 = (AwsCredentialsProvider) context.getBean("s3CredentialsProvider");

                    assertThat(ses).isInstanceOf(StsAssumeRoleCredentialsProvider.class);
                    assertThat(s3).isInstanceOf(StsAssumeRoleCredentialsProvider.class);
                    assertThat(ses).isNotSameAs(s3);
                });
    }

    /**
     * A blank value is how an unset {@code EMAIL_ROLE_ARN} arrives from the
     * environment - it must read as "no separate role", not as a role named "".
     */
    @Test
    void aBlankSesRoleArnIsNotARole() {
        contextRunner.withPropertyValues("app.email.role.arn=").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean("sesCredentialsProvider"))
                    .isSameAs(context.getBean("s3CredentialsProvider"));
        });
    }

    /**
     * Two providers in one context must not make the SES client ambiguous. This is the
     * regression guard for the qualifier on {@code sesV2Client} - without it the whole
     * application fails to start, and it fails for every deployment, not just ones
     * using a separate role.
     */
    @Test
    void theSesClientResolvesDespiteTwoCredentialProvidersBeingPresent() {
        contextRunner.withPropertyValues("app.email.role.arn=" + SES_ROLE).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(SesV2Client.class);
        });
    }
}
