package com.procurepal_services.stock_bridge_api.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.stock.dto.StockOutRequest;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Pins what a stock-out request hashes to TODAY, before SELLING_PRICE_PLAN.md Phase 1 adds a reason,
 * a sale id and a sale amount to {@link StockOutRequest} (plan section 4.3).
 *
 * <p>Why it matters: a stock-out saved on a phone offline is sent - and, if the reply is lost, sent
 * again - with the same Idempotency-Key, possibly either side of a deploy. If the deploy changes what
 * an unchanged request hashes to, the retry is refused as "key reused" for a write the server already
 * recorded, and the person sees a conflict they did nothing to cause. New optional fields are safe
 * only because nulls are left out of the hashed JSON; these tests fail the moment that stops being
 * true.
 *
 * <p><b>If this fails after you changed the request record:</b> a request that does not use your
 * new field must still hash exactly as below. Do not update the literals to make it pass - that is
 * the bug this test exists to catch.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class StockIdempotencyHashPinTest {

    private static final UUID PRODUCT = UUID.fromString("7a1c2a5e-0000-4000-8000-000000000001");
    private static final UUID LOT = UUID.fromString("7a1c2a5e-0000-4000-8000-000000000002");

    @Autowired
    private StockIdempotencyService idempotencyService;

    @Test
    void aSimpleStockOutHashesExactlyAsBeforePhaseOne() {
        StockOutRequest request = new StockOutRequest(3, new BigDecimal("5000.00"), "front counter", "CARTON", null);

        assertThat(idempotencyService.hashMaterial("STOCK_OUT", PRODUCT, request)).isEqualTo(PINNED_SIMPLE_MATERIAL);
        assertThat(idempotencyService.hash("STOCK_OUT", PRODUCT, request)).isEqualTo(PINNED_SIMPLE_HASH);
    }

    @Test
    void aStockOutWithChosenDeliveriesHashesExactlyAsBeforePhaseOne() {
        StockOutRequest request = new StockOutRequest(
                12, null, null, null, List.of(new StockOutRequest.Allocation(LOT, 12)));

        assertThat(idempotencyService.hash("STOCK_OUT", PRODUCT, request)).isEqualTo(PINNED_ALLOCATED_HASH);
    }

    @Test
    void nullFieldsAreLeftOutOfWhatIsHashed() {
        // The rule every later optional field relies on: absent and null hash the same.
        String material = idempotencyService.hashMaterial("STOCK_OUT", PRODUCT, new StockOutRequest(1, null, null));

        assertThat(material).doesNotContain("null").doesNotContain("unitPrice").doesNotContain("note");
    }

    @Test
    void timingFieldsStillDoNotChangeTheHash() {
        StockOutRequest sentOnline = new StockOutRequest(2, null, null, null, null, null, null);
        StockOutRequest resentFromOutbox = new StockOutRequest(
                2, null, null, null, null, OffsetDateTime.parse("2026-10-19T09:00:00+01:00"), true);

        assertThat(idempotencyService.hash("STOCK_OUT", PRODUCT, resentFromOutbox))
                .isEqualTo(idempotencyService.hash("STOCK_OUT", PRODUCT, sentOnline));
    }

    // Captured on develop at 1c14ab7, before any Phase 1 change to StockOutRequest.
    private static final String PINNED_SIMPLE_MATERIAL =
            "STOCK_OUT|7a1c2a5e-0000-4000-8000-000000000001|"
                    + "{\"quantity\":3,\"unitPrice\":5000.00,\"note\":\"front counter\",\"unit\":\"CARTON\"}";
    private static final String PINNED_SIMPLE_HASH = "ebc9f54347b3d0dad55d36406c02d509628c85d0dd4c728c4a91ef2eea9ea1e6";
    private static final String PINNED_ALLOCATED_HASH = "7ae063f62d53aeb8f27188ed3a5a0f56b1b5fe2095f3589bed13fb6d5b3f165d";
}
