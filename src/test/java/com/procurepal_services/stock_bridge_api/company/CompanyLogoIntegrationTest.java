package com.procurepal_services.stock_bridge_api.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.company.dto.CompanyResponse;
import com.procurepal_services.stock_bridge_api.storage.S3ImageService;
import com.procurepal_services.stock_bridge_api.storage.UploadResult;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/** PURCHASE_ORDER_PLAN.md section 3.6: a company sets, changes and removes its own logo. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class CompanyLogoIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @MockitoBean
    private S3ImageService s3ImageService;

    @Test
    void aLogoIsSavedShownOnTheCompanyAndCanBeTakenOff() {
        TenantLoginResponse tenant = signup("Logo Co");
        when(s3ImageService.isConfigured()).thenReturn(true);
        when(s3ImageService.uploadCompanyLogo(any()))
                .thenReturn(UploadResult.success("https://cdn.example.com/logo.png"));

        assertThat(company(tenant).logoUrl()).isNull();

        ResponseEntity<CompanyResponse> saved = upload(tenant, CompanyResponse.class);
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(saved.getBody().logoUrl()).isEqualTo("https://cdn.example.com/logo.png");
        assertThat(company(tenant).logoUrl()).isEqualTo("https://cdn.example.com/logo.png");

        ResponseEntity<CompanyResponse> removed = restTemplate.exchange(
                "/api/company/logo", HttpMethod.DELETE, new HttpEntity<>(auth(tenant)), CompanyResponse.class);
        assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(company(tenant).logoUrl()).isNull();
    }

    @Test
    void refusalsAreInPlainWords() {
        TenantLoginResponse tenant = signup("Refusal Co");
        when(s3ImageService.isConfigured()).thenReturn(true);

        when(s3ImageService.uploadCompanyLogo(any()))
                .thenReturn(UploadResult.failure("File exceeds the maximum size of 5MB"));
        assertThat(upload(tenant, String.class).getBody()).contains("That file is too big (limit 5 MB).");

        when(s3ImageService.uploadCompanyLogo(any()))
                .thenReturn(UploadResult.failure("Unsupported file type - only jpg, jpeg, png, and webp are allowed"));
        assertThat(upload(tenant, String.class).getBody()).contains("Use a JPG, PNG or WebP picture.");

        when(s3ImageService.uploadCompanyLogo(any())).thenReturn(UploadResult.failure("Upload failed: boom"));
        ResponseEntity<String> failed = upload(tenant, String.class);
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(failed.getBody()).contains("We couldn't save that. Try again.").doesNotContain("boom");

        assertThat(company(tenant).logoUrl()).isNull();
    }

    @Test
    void whenStorageIsNotSetUpItSaysSoInsteadOfFailingSilently() {
        TenantLoginResponse tenant = signup("No Storage Co");
        when(s3ImageService.isConfigured()).thenReturn(false);

        ResponseEntity<String> response = upload(tenant, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).contains("Logos can't be saved on this server yet.");
    }

    private <T> ResponseEntity<T> upload(TenantLoginResponse tenant, Class<T> type) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.IMAGE_PNG);
        body.add("file", new HttpEntity<>(new ByteArrayResource(new byte[] {1, 2, 3}) {
            @Override
            public String getFilename() {
                return "logo.png";
            }
        }, partHeaders));
        HttpHeaders headers = auth(tenant);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate.exchange("/api/company/logo", HttpMethod.PUT, new HttpEntity<>(body, headers), type);
    }

    private CompanyResponse company(TenantLoginResponse tenant) {
        return restTemplate.exchange("/api/company", HttpMethod.GET, new HttpEntity<>(auth(tenant)),
                CompanyResponse.class).getBody();
    }

    private HttpHeaders auth(TenantLoginResponse tenant) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tenant.tokens().accessToken());
        return headers;
    }

    private TenantLoginResponse signup(String name) {
        String unique = UUID.randomUUID().toString();
        return restTemplate.postForObject("/api/clients/signup", new ClientSignupRequest(
                name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD),
                TenantLoginResponse.class);
    }
}
