package com.procurepal_services.stock_bridge_api.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Plain Mockito unit test - no Spring context. S3ImageService's job is to
 * never let an S3 problem escape as an exception, so every branch here
 * asserts on the returned UploadResult, never on a thrown exception.
 */
class S3ImageServiceTest {

    private static final AwsProperties CONFIGURED =
            new AwsProperties(
                    "us-east-1", new AwsProperties.S3("test-bucket", "staging"), "AKIAEXAMPLE", "secret", null, null);
    private static final AwsProperties UNCONFIGURED =
            new AwsProperties(null, new AwsProperties.S3(null, null), null, null, null, null);

    private S3Client s3Client;

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
        TenantContext.set(UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void successfulUploadReturnsUrlAndCallsS3() {
        S3ImageService service = new S3ImageService(CONFIGURED, s3Client);
        MockMultipartFile file = new MockMultipartFile("image", "photo.jpg", "image/jpeg", new byte[] {1, 2, 3});

        UploadResult result = service.uploadProductImage(file);

        assertThat(result.success()).isTrue();
        assertThat(result.url()).contains("test-bucket").contains("photo.jpg");
        verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void uploadedKeyIsNamespacedByPrefixAndTenant() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        S3ImageService service = new S3ImageService(CONFIGURED, s3Client);
        MockMultipartFile file = new MockMultipartFile("image", "photo.jpg", "image/jpeg", new byte[] {1, 2, 3});

        service.uploadProductImage(file);

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().key()).startsWith("staging/" + tenantId + "/products/");
    }

    /**
     * A blank prefix is a supported configuration (write at the bucket root),
     * not an error - and it must not produce a leading slash, which S3 would
     * treat as an unnamed first path segment.
     */
    @Test
    void blankPrefixWritesAtTheBucketRootWithoutALeadingSlash() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        S3ImageService service = new S3ImageService(
                new AwsProperties(
                        "us-east-1", new AwsProperties.S3("test-bucket", "  "), "AKIAEXAMPLE", "secret", null,
                        null),
                s3Client);
        MockMultipartFile file = new MockMultipartFile("image", "photo.jpg", "image/jpeg", new byte[] {1, 2, 3});

        service.uploadProductImage(file);

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().key()).startsWith(tenantId + "/products/");
    }

    /** Surrounding slashes are a configuration typo, not a different location. */
    @Test
    void prefixSlashesAreNormalisedAwayRatherThanDoubled() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        S3ImageService service = new S3ImageService(
                new AwsProperties(
                        "us-east-1", new AwsProperties.S3("test-bucket", "/staging/"), "AKIAEXAMPLE", "secret",
                        null, null),
                s3Client);
        MockMultipartFile file = new MockMultipartFile("image", "photo.jpg", "image/jpeg", new byte[] {1, 2, 3});

        service.uploadProductImage(file);

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().key()).startsWith("staging/" + tenantId + "/products/");
    }

    @Test
    void unconfiguredReturnsFailureWithoutCallingS3() {
        S3ImageService service = new S3ImageService(UNCONFIGURED, s3Client);
        MockMultipartFile file = new MockMultipartFile("image", "photo.jpg", "image/jpeg", new byte[] {1, 2, 3});

        UploadResult result = service.uploadProductImage(file);

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).containsIgnoringCase("not configured");
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void rejectsDisallowedFileType() {
        S3ImageService service = new S3ImageService(CONFIGURED, s3Client);
        MockMultipartFile file =
                new MockMultipartFile("image", "payload.exe", "application/octet-stream", new byte[] {1, 2, 3});

        UploadResult result = service.uploadProductImage(file);

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).containsIgnoringCase("unsupported file type");
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void rejectsOversizedFile() {
        S3ImageService service = new S3ImageService(CONFIGURED, s3Client);
        byte[] oversized = new byte[6 * 1024 * 1024];
        MockMultipartFile file = new MockMultipartFile("image", "big.png", "image/png", oversized);

        UploadResult result = service.uploadProductImage(file);

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).containsIgnoringCase("maximum size");
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void sdkFailureIsCaughtAndReturnedAsFailure() {
        S3ImageService service = new S3ImageService(CONFIGURED, s3Client);
        MockMultipartFile file = new MockMultipartFile("image", "photo.png", "image/png", new byte[] {1, 2, 3});
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkClientException.create("connection refused"));

        UploadResult result = service.uploadProductImage(file);

        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).contains("connection refused");
    }
}
