package com.procurepal_services.stock_bridge_api.founding;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.founding.dto.FoundingOfferStatus;
import com.procurepal_services.stock_bridge_api.founding.dto.SetupRequestResponse;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The landing page's two public calls (LANDING_PAGE_PLAN.md, step 3): booking a founding setup with
 * a business name and a WhatsApp number, and the live numbers beside the offer. setup_requests is
 * shared by everything that runs against this database, so these assert on differences, not totals,
 * and each test removes the rows it made.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.founding.total=1000000", "app.founding.weekly-capacity=3", "app.founding.submit-limit=1000"})
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class SetupRequestIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    private final String number = "0803" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999);

    @AfterEach
    void removeWhatThisTestMade() {
        jdbc.update("DELETE FROM setup_requests WHERE whatsapp = ?", WhatsAppNumbers.normalise(number).orElseThrow());
    }

    @Test
    void aRequestTakesAFoundingPlaceAndTheLiveNumbersMove() {
        FoundingOfferStatus before = status();

        ResponseEntity<SetupRequestResponse> response = post("Mama Tee Stores", number, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().founding()).isTrue();
        assertThat(response.getBody().alreadyRequested()).isFalse();
        FoundingOfferStatus after = status();
        assertThat(after.left()).isEqualTo(before.left() - 1);
        assertThat(after.bookedThisWeek()).isEqualTo(before.bookedThisWeek() + 1);
        assertThat(after.endsOn()).isEqualTo(LocalDate.of(2027, 2, 1));
        assertThat(after.open()).isTrue();
    }

    @Test
    void theNumberIsStoredTheWayWhatsAppLinksNeedIt() {
        post("Mama Tee Stores", "0803 " + number.substring(4, 7) + " " + number.substring(7), null);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT business_name, whatsapp, source, status FROM setup_requests WHERE whatsapp = ?",
                "+234" + number.substring(1));
        assertThat(row).containsEntry("business_name", "Mama Tee Stores").containsEntry("source", "landing").containsEntry("status", "NEW");
    }

    @Test
    void theSameNumberAskingTwiceIsOneRequestAndOnePlace() {
        SetupRequestResponse first = post("Mama Tee Stores", number, null).getBody();
        FoundingOfferStatus between = status();

        ResponseEntity<SetupRequestResponse> second = post("Mama Tee Stores Ltd", number, null);

        assertThat(second.getBody().id()).isEqualTo(first.id());
        assertThat(second.getBody().alreadyRequested()).isTrue();
        assertThat(status().left()).isEqualTo(between.left());
    }

    @Test
    void aBookingGoesIntoTheFirstWeekWithRoom() {
        FoundingOfferStatus before = status();
        LocalDate monday = LocalDate.now(ZoneId.of("Africa/Lagos")).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        SetupRequestResponse response = post("Mama Tee Stores", number, null).getBody();

        // weekly-capacity is 3 here: a week already holding 3 books into the next one.
        assertThat(response.setupWeekStarts()).isEqualTo(monday.plusWeeks(before.bookedThisWeek() / 3));
    }

    @Test
    void aNumberThatIsNotANigerianMobileIsRefusedInPlainWords() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/public/setup-requests", Map.of("businessName", "Mama Tee Stores", "whatsapp", "12345"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("message")).isEqualTo("Enter a Nigerian mobile number, like 0803 123 4567.");
    }

    @Test
    void aBlankBusinessNameIsRefused() {
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/public/setup-requests", Map.of("businessName", " ", "whatsapp", number), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aBotThatFillsTheHiddenFieldIsThankedAndForgotten() {
        ResponseEntity<SetupRequestResponse> response = post("Spam Co", number, "https://spam.example");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM setup_requests WHERE whatsapp = ?", Integer.class, "+234" + number.substring(1));
        assertThat(rows).isZero();
    }

    private FoundingOfferStatus status() {
        return restTemplate.getForObject("/api/public/founding-offer", FoundingOfferStatus.class);
    }

    private ResponseEntity<SetupRequestResponse> post(String businessName, String whatsapp, String website) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("businessName", businessName, "whatsapp", whatsapp));
        if (website != null) {
            body.put("website", website);
        }
        return restTemplate.postForEntity("/api/public/setup-requests", body, SetupRequestResponse.class);
    }
}
