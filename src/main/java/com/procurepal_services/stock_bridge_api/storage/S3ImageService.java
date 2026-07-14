package com.procurepal_services.stock_bridge_api.storage;

import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import jakarta.annotation.PostConstruct;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Never lets an S3 problem propagate as an exception to a caller - every
 * failure mode (unconfigured, bad input, the SDK call itself failing) comes
 * back as a UploadResult.failure(...) so callers (ProductManagementService)
 * can save the product anyway and surface a warning instead of a 500.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class S3ImageService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");
    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp");
    private static final long MAX_FILE_SIZE_BYTES = 5L * 1024 * 1024;

    private final AwsProperties awsProperties;
    private final S3Client s3Client;

    @PostConstruct
    void logConfigurationStatus() {
        if (!isConfigured()) {
            log.warn("S3 is not configured (app.aws region/s3.bucket-name/access-key-id/secret-access-key "
                    + "must all be set) - product image uploads will be skipped and reported as a warning "
                    + "until this is fixed.");
        }
    }

    public boolean isConfigured() {
        return awsProperties.isConfigured();
    }

    public UploadResult uploadProductImage(MultipartFile file) {
        if (!isConfigured()) {
            return UploadResult.failure("S3 is not configured");
        }

        UploadResult validationFailure = validate(file);
        if (validationFailure != null) {
            return validationFailure;
        }

        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            return UploadResult.failure("No tenant context set");
        }

        String key = buildObjectKey(tenantId, file.getOriginalFilename());
        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(awsProperties.s3().bucketName())
                            .key(key)
                            .contentType(file.getContentType())
                            .build(),
                    RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
            return UploadResult.success(buildUrl(key));
        } catch (Exception e) {
            log.warn("S3 upload failed for key {}: {}", key, e.getMessage());
            return UploadResult.failure("Upload failed: " + e.getMessage());
        }
    }

    private UploadResult validate(MultipartFile file) {
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            return UploadResult.failure("File exceeds the maximum size of 5MB");
        }
        String extension = extensionOf(file.getOriginalFilename());
        boolean extensionAllowed = extension != null && ALLOWED_EXTENSIONS.contains(extension);
        boolean contentTypeAllowed =
                file.getContentType() != null && ALLOWED_CONTENT_TYPES.contains(file.getContentType());
        if (!extensionAllowed && !contentTypeAllowed) {
            return UploadResult.failure("Unsupported file type - only jpg, jpeg, png, and webp are allowed");
        }
        return null;
    }

    private String extensionOf(String filename) {
        if (filename == null || !filename.contains(".")) {
            return null;
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }

    private String buildObjectKey(UUID tenantId, String originalFilename) {
        return tenantId + "/products/" + UUID.randomUUID() + "-" + sanitizeFilename(originalFilename);
    }

    private String sanitizeFilename(String filename) {
        String safe = filename == null ? "image" : filename.replaceAll("[^a-zA-Z0-9._-]", "_");
        return safe.length() > 100 ? safe.substring(safe.length() - 100) : safe;
    }

    private String buildUrl(String key) {
        return "https://" + awsProperties.s3().bucketName() + ".s3." + awsProperties.region()
                + ".amazonaws.com/" + key;
    }
}
