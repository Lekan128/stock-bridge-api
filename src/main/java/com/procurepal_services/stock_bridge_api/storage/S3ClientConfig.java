package com.procurepal_services.stock_bridge_api.storage;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The S3Client bean is always constructed, even when app.aws is unset or
 * invalid - building the client is just object setup, it never talks to AWS.
 * S3ImageService.isConfigured() (backed by AwsProperties) is what actually
 * gates real use, so a missing/blank config never crashes startup here.
 */
@Configuration
public class S3ClientConfig {

    @Bean
    public S3Client s3Client(AwsProperties awsProperties) {
        // AwsBasicCredentials.create() itself rejects blank strings, so an
        // unconfigured setup needs non-blank placeholders here too - harmless,
        // since S3ImageService.isConfigured() gates real use before any call
        // that would actually send these to AWS.
        String region = blankToDefault(awsProperties.region(), Region.US_EAST_1.id());
        String accessKeyId = blankToDefault(awsProperties.accessKeyId(), "unconfigured");
        String secretAccessKey = blankToDefault(awsProperties.secretAccessKey(), "unconfigured");

        return S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                .build();
    }

    private static String blankToDefault(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }
}
