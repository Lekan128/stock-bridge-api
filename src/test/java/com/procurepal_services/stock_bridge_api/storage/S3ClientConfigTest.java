package com.procurepal_services.stock_bridge_api.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;

/**
 * Asserts which credential shape the context ends up with, not that AWS
 * accepts it - none of these beans talk to AWS when they are built, which is
 * the property that lets the whole configuration be constructed even when
 * app.aws is blank.
 */
class S3ClientConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AwsPropertiesConfig.class, S3ClientConfig.class)
            .withPropertyValues(
                    "app.aws.region=us-east-1",
                    "app.aws.s3.bucket-name=test-bucket",
                    "app.aws.access-key-id=AKIAEXAMPLE",
                    "app.aws.secret-access-key=secret");

    /** Stands in for the application's @ConfigurationPropertiesScan, which a context runner has none of. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AwsProperties.class)
    static class AwsPropertiesConfig {
    }

    @Test
    void withoutARoleArnTheAccessKeyIsUsedAgainstS3Directly() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AwsCredentialsProvider.class);
            assertThat(context.getBean(AwsCredentialsProvider.class)).isInstanceOf(StaticCredentialsProvider.class);
            // No role to assume means nothing to build an STS client for.
            assertThat(context).doesNotHaveBean(StsClient.class);
        });
    }

    @Test
    void withARoleArnCredentialsComeFromAssumingThatRole() {
        contextRunner
                .withPropertyValues("app.aws.role-arn=arn:aws:iam::123456789012:role/procurepal-s3-staging")
                .run(context -> {
                    assertThat(context.getBean(AwsCredentialsProvider.class))
                            .isInstanceOf(StsAssumeRoleCredentialsProvider.class);
                    // Must be a bean, not a private field of the provider above:
                    // StsCredentialsProvider.close() does not close it, so the
                    // context has to be what shuts it down.
                    assertThat(context).hasSingleBean(StsClient.class);
                });
    }

    /** A blank value is how an unset AWS_ROLE_ARN arrives - it must not count as "assume a role". */
    @Test
    void aBlankRoleArnIsTreatedAsNoRole() {
        contextRunner.withPropertyValues("app.aws.role-arn=").run(context -> {
            assertThat(context.getBean(AwsCredentialsProvider.class)).isInstanceOf(StaticCredentialsProvider.class);
            assertThat(context).doesNotHaveBean(StsClient.class);
        });
    }
}
