package com.procurepal_services.stock_bridge_api.founding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.procurepal_services.stock_bridge_api.auth.dto.SuperAdminLoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.SuperAdminLoginResponse;
import com.procurepal_services.stock_bridge_api.entity.SuperAdmin;
import com.procurepal_services.stock_bridge_api.founding.dto.SetupRequestCounts;
import com.procurepal_services.stock_bridge_api.founding.dto.SetupRequestResponse;
import com.procurepal_services.stock_bridge_api.founding.dto.SetupRequestView;
import com.procurepal_services.stock_bridge_api.founding.dto.UpdateSetupRequest;
import com.procurepal_services.stock_bridge_api.repository.SuperAdminRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The team's setup queue and its alert (LANDING_PAGE_PLAN.md, step 4: speed to lead). The table is
 * shared with everything else on this database, so this asserts on its own rows and on differences.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.founding.total=1000000", "app.founding.submit-limit=1000"})
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class SetupRequestQueueIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SuperAdminRepository superAdminRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private SetupRequestAlerts alerts;

    private final String local = "0803" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999);
    private final String e164 = "+234" + local.substring(1);

    @AfterEach
    void removeWhatThisTestMade() {
        jdbc.update("DELETE FROM setup_requests WHERE whatsapp = ?", e164);
    }

    @Test
    void theTeamIsAlertedOnceForANewRequestAndNeverForARepeatOrABot() {
        book("Alert Shop", local, null);
        book("Alert Shop", local, null);
        book("Bot Shop", "0806" + local.substring(4), "https://spam.example");

        verify(alerts, times(1)).newRequest(eq("Alert Shop"), eq(e164), eq("landing"), anyBoolean(), any(), any());
        verify(alerts, times(0)).newRequest(eq("Bot Shop"), any(), any(), anyBoolean(), any(), any());
    }

    @Test
    void aNewRequestWaitsInTheQueueUntilSomebodyRepliesAndTheReplyTimeIsKept() {
        String token = superAdminToken();
        SetupRequestCounts before = counts(token);
        UUID id = book("Queue Shop", local, null).id();

        assertThat(counts(token).waiting()).isEqualTo(before.waiting() + 1);
        SetupRequestView waiting = find(token, "NEW", id);
        assertThat(waiting.businessName()).isEqualTo("Queue Shop");
        assertThat(waiting.whatsapp()).isEqualTo(e164);
        assertThat(waiting.contactedAt()).isNull();

        ResponseEntity<SetupRequestView> contacted = patch(token, id, new UpdateSetupRequest("CONTACTED", "Asked for the list"));
        assertThat(contacted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(contacted.getBody().contactedAt()).isNotNull();
        assertThat(contacted.getBody().note()).isEqualTo("Asked for the list");
        assertThat(find(token, "IN_PROGRESS", id)).isNotNull();
        assertThat(counts(token).waiting()).isEqualTo(before.waiting());

        // Later moves keep the first reply time and, without a note, the note.
        SetupRequestView loaded = patch(token, id, new UpdateSetupRequest("LOADED", null)).getBody();
        assertThat(loaded.contactedAt()).isEqualTo(contacted.getBody().contactedAt());
        assertThat(loaded.note()).isEqualTo("Asked for the list");
        assertThat(counts(token).medianMinutesToContact()).isNotNull();
    }

    @Test
    void onlySuperAdminsSeeTheQueueAndMistakesAreRefusedPlainly() {
        assertThat(restTemplate.getForEntity("/api/superadmin/setup-requests", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        String token = superAdminToken();
        assertThat(patchRaw(token, UUID.randomUUID(), new UpdateSetupRequest("CONTACTED", null)).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        UUID id = book("Mistake Shop", local, null).id();
        assertThat(patchRaw(token, id, new UpdateSetupRequest("DONE", null)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(restTemplate.exchange("/api/superadmin/setup-requests?tab=SOMETHING", HttpMethod.GET,
                new HttpEntity<>(auth(token)), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void theReplyLinkOpensAChatWithTheFirstMessageTyped() {
        String url = SetupRequestAlerts.replyUrl("+2348031234567", "Mama Tee & Sons");
        assertThat(url).startsWith("https://wa.me/2348031234567?text=Hello%20Mama%20Tee%20%26%20Sons%2C%20this%20is%20Procurepaddy.");
        assertThat(SetupRequestAlerts.jsonEscape("a \"b\"\n\\c")).isEqualTo("a \\\"b\\\"\\n\\\\c");
    }

    private SetupRequestResponse book(String businessName, String whatsapp, String website) {
        Map<String, String> body = website == null
                ? Map.of("businessName", businessName, "whatsapp", whatsapp)
                : Map.of("businessName", businessName, "whatsapp", whatsapp, "website", website);
        return restTemplate.postForObject("/api/public/setup-requests", body, SetupRequestResponse.class);
    }

    private SetupRequestCounts counts(String token) {
        return restTemplate.exchange("/api/superadmin/setup-requests/counts", HttpMethod.GET,
                new HttpEntity<>(auth(token)), SetupRequestCounts.class).getBody();
    }

    private SetupRequestView find(String token, String tab, UUID id) {
        ResponseEntity<PageResponse<SetupRequestView>> page = restTemplate.exchange(
                "/api/superadmin/setup-requests?tab=" + tab + "&size=1000", HttpMethod.GET,
                new HttpEntity<>(auth(token)), new ParameterizedTypeReference<>() {});
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        return page.getBody().content().stream().filter(row -> row.id().equals(id)).findFirst().orElse(null);
    }

    private ResponseEntity<SetupRequestView> patch(String token, UUID id, UpdateSetupRequest body) {
        return restTemplate.exchange("/api/superadmin/setup-requests/" + id, HttpMethod.PATCH,
                new HttpEntity<>(body, auth(token)), SetupRequestView.class);
    }

    private ResponseEntity<String> patchRaw(String token, UUID id, UpdateSetupRequest body) {
        return restTemplate.exchange("/api/superadmin/setup-requests/" + id, HttpMethod.PATCH,
                new HttpEntity<>(body, auth(token)), String.class);
    }

    private String superAdminToken() {
        String username = "superadmin-" + UUID.randomUUID();
        superAdminRepository.save(SuperAdmin.builder().username(username).passwordHash(passwordEncoder.encode(PASSWORD)).build());
        return restTemplate.postForObject("/api/superadmin/auth/login", new SuperAdminLoginRequest(username, PASSWORD),
                SuperAdminLoginResponse.class).tokens().accessToken();
    }

    private HttpHeaders auth(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private record PageResponse<T>(List<T> content) {
    }
}
