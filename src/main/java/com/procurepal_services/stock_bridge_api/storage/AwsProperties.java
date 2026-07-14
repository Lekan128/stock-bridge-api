package com.procurepal_services.stock_bridge_api.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All fields are optional at the binding level (see application.yml, which
 * defaults the unset ones to blank rather than failing placeholder
 * resolution) - isConfigured() is the single source of truth for whether S3
 * is actually usable. See S3ImageService for how that gets enforced.
 */
@ConfigurationProperties(prefix = "app.aws")
public record AwsProperties(String region, S3 s3, String accessKeyId, String secretAccessKey) {

    public record S3(String bucketName) {
    }

    public boolean isConfigured() {
        return notBlank(region) && s3 != null && notBlank(s3.bucketName()) && notBlank(accessKeyId)
                && notBlank(secretAccessKey);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
