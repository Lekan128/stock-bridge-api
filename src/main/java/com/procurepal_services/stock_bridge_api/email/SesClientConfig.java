package com.procurepal_services.stock_bridge_api.email;

import com.procurepal_services.stock_bridge_api.storage.AwsProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;

/**
 * Builds the SES client, on exactly the terms
 * {@link com.procurepal_services.stock_bridge_api.storage.S3ClientConfig} builds the
 * S3 one: every bean here is constructed even when nothing is configured, because
 * assembling a client and a credentials provider talks to nobody.
 * {@link EmailSender#isConfigured()} is what gates real use, so a deploy with no SES
 * setup starts cleanly and simply does not send.
 *
 * <h2>Two credential shapes, chosen by app.email.role.arn</h2>
 * <b>Blank</b> - SES borrows the single {@link AwsCredentialsProvider} the
 * application already has, which is whatever {@code app.aws} configured: a direct
 * access key, or the S3 role session. This was the original behaviour and remains
 * the default, so a deployment that never sets the new property is unaffected.
 *
 * <p><b>Set</b> - SES assumes its own role, separate from S3's, from the same IAM
 * user keys. {@link SesRoleProperties} carries the reasoning; the short version is
 * that fusing "may write product images" and "may send mail as the company" into one
 * credential makes each the other's blast radius.
 *
 * <h2>Why the assumed session is built from the USER keys, not the S3 session</h2>
 * The obvious implementation chains: take the existing provider (already an S3 role
 * session) and assume the SES role from it. That is wrong twice. A chained session is
 * capped at one hour by AWS regardless of the role's configured maximum, and it needs
 * the S3 role's trust policy to name the SES role - which re-couples the two roles
 * that were just separated. So this goes back to the long-lived user credentials,
 * whose only job in either path is to call {@code sts:AssumeRole}, and assumes the SES
 * role directly. One secret to rotate, two independent roles.
 *
 * <h2>A note for whoever next edits S3ClientConfig</h2>
 * There are now TWO beans of type {@link AwsCredentialsProvider} in this context.
 * Spring resolves {@code S3ClientConfig.s3Client}'s untyped parameter by falling back
 * to matching the parameter NAME against the bean name, so that parameter being called
 * {@code s3CredentialsProvider} is load-bearing - renaming it would produce an
 * ambiguity failure at startup. This class avoids relying on that by qualifying its
 * own injection point explicitly.
 */
@Configuration
public class SesClientConfig {

    /**
     * Exists only when SES actually assumes its own role. It is a separate bean rather
     * than something the provider below constructs privately for the same reason
     * S3ClientConfig gives: {@code StsAssumeRoleCredentialsProvider.close()} closes
     * only its own session cache and leaves the {@link StsClient} it was handed open,
     * so that client needs an owner which will shut it down - and a Spring bean is one.
     *
     * <p>The condition reads the raw property rather than {@link SesRoleProperties}
     * because a {@code @Conditional} is evaluated while deciding which bean definitions
     * exist, long before anything is bound.
     */
    @Bean
    @ConditionalOnExpression("!'${app.email.role.arn:}'.isBlank()")
    public StsClient sesStsClient(EmailProperties emailProperties, AwsProperties awsProperties) {
        return StsClient.builder()
                .region(Region.of(resolveRegion(emailProperties, awsProperties)))
                .credentialsProvider(userCredentials(awsProperties))
                .build();
    }

    /**
     * @param sharedCredentialsProvider the application-wide provider built by
     *     S3ClientConfig. Injected by name, because by the time this method runs there
     *     are two candidates by type and one of them is the bean being defined here.
     */
    @Bean("sesCredentialsProvider")
    public AwsCredentialsProvider sesCredentialsProvider(
            SesRoleProperties sesRoleProperties,
            AwsProperties awsProperties,
            org.springframework.beans.factory.ObjectProvider<StsClient> sesStsClient,
            @Qualifier("s3CredentialsProvider") AwsCredentialsProvider sharedCredentialsProvider) {
        if (!sesRoleProperties.assumesRole()) {
            return sharedCredentialsProvider;
        }

        return StsAssumeRoleCredentialsProvider.builder()
                .stsClient(sesStsClient.getObject())
                .refreshRequest(AssumeRoleRequest.builder()
                        .roleArn(sesRoleProperties.arn().trim())
                        .roleSessionName(sesRoleProperties.resolvedSessionName())
                        .build())
                // Refresh on a background thread rather than on whichever email happens
                // to be sending as the session ages out. Same rationale as S3's, with
                // one extra wrinkle: sends already run on the email executor after the
                // caller's transaction has committed, so a blocking STS refresh there
                // would not be visible as request latency - it would be visible as mail
                // that goes out late, which is harder to notice and harder to explain.
                .asyncCredentialUpdateEnabled(true)
                .build();
    }

    @Bean
    public SesV2Client sesV2Client(
            EmailProperties emailProperties,
            AwsProperties awsProperties,
            @Qualifier("sesCredentialsProvider") AwsCredentialsProvider credentialsProvider) {
        return SesV2Client.builder()
                .region(Region.of(resolveRegion(emailProperties, awsProperties)))
                .credentialsProvider(credentialsProvider)
                .build();
    }

    /**
     * The long-lived IAM user keys. {@code AwsBasicCredentials.create} rejects blank
     * strings outright, so an unconfigured setup needs non-blank placeholders -
     * harmless, because {@link EmailSender#isConfigured()} gates every real call long
     * before these would reach AWS.
     */
    private static StaticCredentialsProvider userCredentials(AwsProperties awsProperties) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(
                blankToDefault(awsProperties == null ? null : awsProperties.accessKeyId(), "unconfigured"),
                blankToDefault(awsProperties == null ? null : awsProperties.secretAccessKey(), "unconfigured")));
    }

    /**
     * {@code app.email.region} exists because the verified sending identity and the
     * image bucket need not share a region, and SES is not available in every region
     * S3 is. Falls back to {@code app.aws.region} - the common case, one region for
     * everything - and then to us-east-1, mirroring S3ClientConfig's own fallback so
     * neither client can fail to build over an unset region.
     */
    private static String resolveRegion(EmailProperties emailProperties, AwsProperties awsProperties) {
        if (emailProperties != null && notBlank(emailProperties.region())) {
            return emailProperties.region();
        }
        if (awsProperties != null && notBlank(awsProperties.region())) {
            return awsProperties.region();
        }
        return Region.US_EAST_1.id();
    }

    private static String blankToDefault(String value, String fallback) {
        return notBlank(value) ? value : fallback;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
