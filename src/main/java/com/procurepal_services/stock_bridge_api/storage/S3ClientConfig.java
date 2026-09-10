package com.procurepal_services.stock_bridge_api.storage;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;

/**
 * Every bean here is constructed even when app.aws is unset or invalid -
 * building a client or a credentials provider is just object setup, none of
 * it talks to AWS. S3ImageService.isConfigured() (backed by AwsProperties) is
 * what actually gates real use, so a missing/blank config never crashes
 * startup here.
 */
@Configuration
public class S3ClientConfig {

    private static final String DEFAULT_ROLE_SESSION_NAME = "stock-bridge-api";

    /**
     * Only exists when a role is actually being assumed. It is a separate bean
     * rather than something the provider below builds for itself because
     * StsCredentialsProvider.close() closes only its session cache and leaves
     * the StsClient it was handed open - so the client needs an owner that
     * will shut it down, and a Spring bean is one (Spring infers close() on
     * any AutoCloseable bean).
     *
     * The expression reads the raw property rather than AwsProperties because
     * a @Conditional is evaluated while deciding which bean definitions exist,
     * long before anything is bound.
     */
    @Bean
    @ConditionalOnExpression("!'${app.aws.role-arn:}'.isBlank()")
    public StsClient stsClient(AwsProperties awsProperties) {
        return StsClient.builder()
                .region(Region.of(blankToDefault(awsProperties.region(), Region.US_EAST_1.id())))
                .credentialsProvider(userCredentials(awsProperties))
                .build();
    }

    /**
     * Two supported credential shapes, chosen by whether app.aws.role-arn is set.
     *
     * <p>Blank: the configured access key is used against S3 directly, and so
     * must carry the S3 permissions itself.
     *
     * <p>Set: the access key is used only to call sts:AssumeRole, and the role
     * carries the S3 permissions. The keys then grant nothing on their own,
     * and what the application actually holds is a session that expires on its
     * own - which is the point of doing it this way.
     */
    @Bean
    public AwsCredentialsProvider s3CredentialsProvider(
            AwsProperties awsProperties, ObjectProvider<StsClient> stsClient) {
        if (!awsProperties.assumesRole()) {
            return userCredentials(awsProperties);
        }

        return StsAssumeRoleCredentialsProvider.builder()
                .stsClient(stsClient.getObject())
                .refreshRequest(AssumeRoleRequest.builder()
                        .roleArn(awsProperties.roleArn())
                        .roleSessionName(
                                blankToDefault(awsProperties.roleSessionName(), DEFAULT_ROLE_SESSION_NAME))
                        .build())
                // Refresh on a background thread instead of on whichever upload
                // happens to arrive as the session ages out. The SDK prefetches
                // 5 minutes before expiry and only treats credentials as stale
                // in the final minute, so with this on, no request ever waits
                // on STS: the session is replaced well before any request could
                // find it expired. Left off, the same refresh still happens,
                // but one unlucky caller per session pays the STS round trip.
                //
                // This is also what makes the role's 1-hour max session
                // duration a non-issue - see DEPLOYMENT.md.
                .asyncCredentialUpdateEnabled(true)
                .build();
    }

    @Bean
    public S3Client s3Client(AwsProperties awsProperties, AwsCredentialsProvider s3CredentialsProvider) {
        return S3Client.builder()
                .region(Region.of(blankToDefault(awsProperties.region(), Region.US_EAST_1.id())))
                .credentialsProvider(s3CredentialsProvider)
                .build();
    }

    /**
     * The long-lived IAM user keys, whatever they are ultimately used for.
     * AwsBasicCredentials.create() itself rejects blank strings, so an
     * unconfigured setup needs non-blank placeholders - harmless, since
     * S3ImageService.isConfigured() gates real use before any call that would
     * actually send these to AWS.
     */
    private static StaticCredentialsProvider userCredentials(AwsProperties awsProperties) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(
                blankToDefault(awsProperties.accessKeyId(), "unconfigured"),
                blankToDefault(awsProperties.secretAccessKey(), "unconfigured")));
    }

    private static String blankToDefault(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }
}
