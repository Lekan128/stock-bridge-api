package com.procurepal_services.stock_bridge_api.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All fields are optional at the binding level (see application.yml, which
 * defaults the unset ones to blank rather than failing placeholder
 * resolution) - isConfigured() is the single source of truth for whether S3
 * is actually usable. See S3ImageService for how that gets enforced.
 */
@ConfigurationProperties(prefix = "app.aws")
public record AwsProperties(
        String region,
        S3 s3,
        String accessKeyId,
        String secretAccessKey,
        String roleArn,
        String roleSessionName) {

    /**
     * keyPrefix is deliberately NOT part of isConfigured(): it is a namespace,
     * not a credential, and a blank one is a perfectly valid (if untidy)
     * setup that writes to the root of the bucket. See
     * S3ImageService.buildObjectKey for how it is applied.
     */
    public record S3(String bucketName, String keyPrefix) {
    }

    /**
     * roleArn is not part of this either, and for a different reason than
     * keyPrefix: both credential shapes are complete without it. Blank means
     * the access key is used against S3 directly; set means the access key is
     * only good for assuming that role, and the role carries the S3
     * permissions. Either way it is the access key that has to be present, so
     * that is what is checked here. See S3ClientConfig.
     */
    public boolean isConfigured() {
        return notBlank(region) && s3 != null && notBlank(s3.bucketName()) && notBlank(accessKeyId)
                && notBlank(secretAccessKey);
    }

    public boolean assumesRole() {
        return notBlank(roleArn);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
