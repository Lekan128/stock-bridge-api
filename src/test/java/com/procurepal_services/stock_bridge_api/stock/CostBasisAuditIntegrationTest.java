package com.procurepal_services.stock_bridge_api.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorResponse;
import com.procurepal_services.stock_bridge_api.entity.MovementType;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.entity.StockMovement;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.repository.StockMovementRepository;
import com.procurepal_services.stock_bridge_api.stock.dto.CostBasisAnomalyResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockInRequest;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * UNIT_UX_REMEDIATION_PLAN.md Phase 0's cost audit, end to end.
 *
 * <h2>Why this test writes damaged rows directly rather than through the API</h2>
 * The damage it hunts can no longer be created through any public path - that is the point of the
 * rest of M1's work. {@code stockIn} now converts price and quantity by the same factor, so
 * posting "20 bags at &#8358;45,000" produces the CORRECT &#8358;900 per kg and nothing to find.
 * Reproducing the defect therefore means writing the rows the old code would have written, which
 * is what {@link #seedPreRemediationDamage} does through the repositories: a
 * {@code unit_price_at_time} of &#8358;45,000 against a quantity of 1,000 kg, and a
 * {@code cost_price} to match. That is a faithful reconstruction of a pre-V21 row, and building
 * it any other way would mean testing the detector against data the detector will never see.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class CostBasisAuditIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    private static final Pattern UUID_ANYWHERE =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    /**
     * The brief, exactly: one product carrying a 50x-inflated cost written through the old path,
     * one priced correctly, and only the first is reported.
     */
    @Test
    void onlyTheProductWithAPackInflatedCostIsReported() {
        TenantLoginResponse admin = signup("Cost Audit Co");
        CompanyVendorResponse supplier = createSupplier(admin, "Dangote Nigeria Plc");

        // Damaged: rice packed 50 kg to the bag whose cost was typed per bag (N45,000) and
        // recorded per kg by the old code. The true cost is N900/kg.
        ProductResponse damaged = createRice(admin, "AUDIT-BROKEN", new BigDecimal("1200"));
        seedPreRemediationDamage(damaged.id(), new BigDecimal("45000"), 1000);

        // Healthy: same shape, same supplier, entered through today's corrected path.
        ProductResponse healthy = createRice(admin, "AUDIT-OK", new BigDecimal("1200"));
        stockIn(admin, healthy.id(), 20, new BigDecimal("45000"), "BAG", supplier.id());

        List<CostBasisAnomalyResponse> findings = anomalies(admin);

        assertThat(findings).extracting(CostBasisAnomalyResponse::productId).containsExactly(damaged.id());
        assertThat(findings).extracting(CostBasisAnomalyResponse::productId).doesNotContain(healthy.id());

        // The healthy product genuinely is healthy, not merely unreported: N900/kg cost against
        // an N1,200/kg selling price, and its deliveries agree with it.
        ResponseEntity<ProductResponse> healthyNow = restTemplate.exchange(
                "/api/products/" + healthy.id(),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(admin)),
                ProductResponse.class);
        assertThat(healthyNow.getBody().costPrice()).isEqualByComparingTo("900");
    }

    /**
     * The finding carries the evidence a human needs to make the call - what is stored, how far
     * out it is, and the fact that the factor matches the pack, which is the arithmetic signature
     * of the bug rather than a general smell.
     */
    @Test
    void theFindingNamesTheRatioThePackAndWhatMostLikelyHappened() {
        TenantLoginResponse admin = signup("Cost Audit Evidence Co");
        ProductResponse damaged = createRice(admin, "AUDIT-EV", new BigDecimal("1200"));
        seedPreRemediationDamage(damaged.id(), new BigDecimal("45000"), 1000);

        CostBasisAnomalyResponse finding = anomalies(admin).get(0);

        assertThat(finding.productName()).isEqualTo("Rice 50kg");
        assertThat(finding.sku()).isEqualTo("AUDIT-EV");
        assertThat(finding.stockUnitSymbol()).isEqualTo("kg");
        assertThat(finding.costPrice()).isEqualByComparingTo("45000");
        assertThat(finding.packagingSize()).isEqualByComparingTo("50");

        // A buying company has no selling price at all - ProductManagementService discards
        // unitPrice for any tenant that is not a seller - so the economic signal is blind here,
        // and the ledger agrees with itself because both halves were inflated together. Nothing
        // was measured, so nothing is reported as measured.
        assertThat(finding.sellingPrice()).isNull();
        assertThat(finding.medianMovementPrice()).isEqualByComparingTo("45000");
        assertThat(finding.largestRatio()).isNull();
        assertThat(finding.ratioMatchesPackSize()).isFalse();

        // What catches it is how the row was written, not what it says.
        assertThat(finding.signals()).containsExactly(CostBasisAuditService.SIGNAL_ENTERED_IN_PACKS_BEFORE_FIX);
        assertThat(finding.summary())
                .contains("Rice 50kg")
                .contains("per kg")
                .contains("entered in packs")
                .contains("50 kg")
                .contains("we cannot tell from the record alone")
                .contains("Check it against a delivery note");
        assertNoUuid(finding.summary());
    }

    /**
     * The class-javadoc claim, asserted rather than asserted-in-prose: Phase 0's stated heuristic
     * alone would find nothing here, because the old code inflated the movement price and the
     * cost price by the same factor at the same moment. This is why a second signal exists.
     */
    @Test
    void thePlansStatedHeuristicAloneCannotSeeAUniformlyDamagedProduct() {
        TenantLoginResponse admin = signup("Cost Audit Blindspot Co");
        ProductResponse damaged = createRice(admin, "AUDIT-BLIND", new BigDecimal("1200"));
        seedPreRemediationDamage(damaged.id(), new BigDecimal("45000"), 1000);

        CostBasisAnomalyResponse finding = anomalies(admin).get(0);

        // cost / median = 45000 / 45000 = 1.0. The plan's rule sees perfect agreement, because
        // the old code inflated both numbers by the same factor at the same moment.
        assertThat(finding.costPrice()).isEqualByComparingTo(finding.medianMovementPrice());
        assertThat(finding.signals()).doesNotContain(CostBasisAuditService.SIGNAL_COST_DISAGREES_WITH_DELIVERIES);
        // And the economic cross-check is blind too, because a buying company has no selling
        // price. Between them the two comparison rules would report NOTHING for the main case.
        assertThat(finding.sellingPrice()).isNull();
        assertThat(finding.signals()).doesNotContain(CostBasisAuditService.SIGNAL_COST_EXCEEDS_SELLING_PRICE);
        // The provenance signal is the only reason this product is in the report at all.
        assertThat(finding.signals()).containsExactly(CostBasisAuditService.SIGNAL_ENTERED_IN_PACKS_BEFORE_FIX);
    }

    /**
     * The mixed case - some deliveries entered correctly, one through the broken path - is what
     * Phase 0's stated heuristic was written for, and it does fire there.
     */
    @Test
    void aProductWhoseHistoryMixesBrokenAndCorrectEntriesTripsThePlansOwnRule() {
        TenantLoginResponse admin = signup("Cost Audit Mixed Co");
        CompanyVendorResponse supplier = createSupplier(admin, "Dangote Nigeria Plc");

        // No selling price at all - a buying company's private stock, where the economic signal
        // is blind and the ledger comparison is the only one available.
        ProductResponse product = createRice(admin, "AUDIT-MIXED", null);

        // Three honest deliveries at N900/kg, so the median of the history is N900...
        stockIn(admin, product.id(), 20, new BigDecimal("45000"), "BAG", supplier.id());
        stockIn(admin, product.id(), 20, new BigDecimal("45000"), "BAG", supplier.id());
        stockIn(admin, product.id(), 20, new BigDecimal("45000"), "BAG", supplier.id());
        // ...and one pre-remediation row that dragged the stored cost price up with it.
        seedPreRemediationDamage(product.id(), new BigDecimal("45000"), 1000);

        CostBasisAnomalyResponse finding = anomalies(admin).get(0);
        assertThat(finding.productId()).isEqualTo(product.id());
        assertThat(finding.sellingPrice()).isNull();
        assertThat(finding.medianMovementPrice()).isEqualByComparingTo("900");
        assertThat(finding.signals()).contains(CostBasisAuditService.SIGNAL_COST_DISAGREES_WITH_DELIVERIES);
        // 45,000 / 900 = 50 - and 50 is exactly the pack size, which is the smoking gun.
        assertThat(finding.largestRatio()).isEqualByComparingTo("50");
        assertThat(finding.ratioMatchesPackSize()).isTrue();
        assertThat(finding.summary()).contains("typed per pack");
    }

    /** A tenant sees only its own catalogue - this is a report about one company's money. */
    @Test
    void theAuditIsTenantScoped() {
        TenantLoginResponse tenantA = signup("Cost Audit Tenant A");
        TenantLoginResponse tenantB = signup("Cost Audit Tenant B");

        ProductResponse damaged = createRice(tenantA, "AUDIT-ISO", new BigDecimal("1200"));
        seedPreRemediationDamage(damaged.id(), new BigDecimal("45000"), 1000);

        assertThat(anomalies(tenantA)).extracting(CostBasisAnomalyResponse::productId).containsExactly(damaged.id());
        assertThat(anomalies(tenantB)).isEmpty();
    }

    /** A clean catalogue reports nothing rather than reporting something reassuring. */
    @Test
    void aHealthyCatalogueProducesAnEmptyReport() {
        TenantLoginResponse admin = signup("Cost Audit Clean Co");
        CompanyVendorResponse supplier = createSupplier(admin, "Dangote Nigeria Plc");
        ProductResponse product = createRice(admin, "AUDIT-CLEAN", new BigDecimal("1200"));
        stockIn(admin, product.id(), 20, new BigDecimal("45000"), "BAG", supplier.id());

        assertThat(anomalies(admin)).isEmpty();
    }

    /**
     * The guard that keeps this report from growing useless over time. A delivery recorded TODAY
     * in packs carries a pack snapshot on its row too - the same {@code packaging_size} the
     * suspect pre-V21 rows carry. What separates them is {@code entered_unit}, which V21 added
     * precisely so that "we know which unit this was typed in" became a recordable fact.
     *
     * <p>If the discriminator were the pack snapshot alone, every correctly-recorded pack
     * delivery from now on would be flagged, the report would fill with noise, and the genuine
     * pre-V21 findings would be unfindable inside it.
     */
    @Test
    void aDeliveryEnteredInPacksAfterTheFixIsNotFlaggedEvenThoughItCarriesAPackSnapshot() {
        TenantLoginResponse admin = signup("Cost Audit Post Fix Co");
        CompanyVendorResponse supplier = createSupplier(admin, "Dangote Nigeria Plc");
        ProductResponse product = createRice(admin, "AUDIT-POSTFIX", null);

        // Entered in packs, with an explicit per-delivery pack - so the movement row carries both
        // a packaging_size snapshot AND an entered_unit. Correctly recorded: N900/kg.
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/products/" + product.id() + "/stock/stock-in",
                HttpMethod.POST,
                new HttpEntity<>(
                        new StockInRequest(
                                20, new BigDecimal("45000"), null, "BAG", supplier.id(), "BAG", new BigDecimal("50")),
                        authHeaders(admin)),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(anomalies(admin)).isEmpty();
    }

    /** Nothing is repaired: the suspect values are exactly as they were after the report runs. */
    @Test
    void theAuditNeverRewritesTheValuesItReports() {
        TenantLoginResponse admin = signup("Cost Audit Read Only Co");
        ProductResponse damaged = createRice(admin, "AUDIT-RO", new BigDecimal("1200"));
        seedPreRemediationDamage(damaged.id(), new BigDecimal("45000"), 1000);

        assertThat(anomalies(admin)).hasSize(1);
        // Run it twice - a repair would make the second run empty.
        assertThat(anomalies(admin)).hasSize(1);

        ResponseEntity<ProductResponse> after = restTemplate.exchange(
                "/api/products/" + damaged.id(),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(admin)),
                ProductResponse.class);
        assertThat(after.getBody().costPrice()).isEqualByComparingTo("45000");
    }

    // -----------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------

    /**
     * Writes the ledger row and the cost price the PRE-V21 code would have written for
     * "{@code quantityBaseUnits} kg received at {@code perPackPrice} per bag": the quantity
     * converted, the price not. See the class javadoc for why this is done directly.
     */
    void seedPreRemediationDamage(UUID productId, BigDecimal perPackPrice, int quantityBaseUnits) {
        Product product = productRepository.findById(productId).orElseThrow();
        TenantContext.set(product.getClientId());
        try {
            product.setCostPrice(perPackPrice);
            product.setQuantityOnHand(quantityBaseUnits);
            productRepository.saveAndFlush(product);

            stockMovementRepository.saveAndFlush(StockMovement.builder()
                    .product(product)
                    .movementType(MovementType.IN)
                    .quantity(quantityBaseUnits)
                    // The price NOT converted - the whole of P0-1 in one field.
                    .unitPriceAtTime(perPackPrice)
                    // The pack snapshot the old stockIn wrote, and no entered_unit, because the
                    // column did not exist yet. Together these are the row's provenance.
                    .packagingUnit("BAG")
                    .packagingSize(new BigDecimal("50"))
                    .note("pre-remediation delivery")
                    .build());
        } finally {
            TenantContext.clear();
        }
    }

    private List<CostBasisAnomalyResponse> anomalies(TenantLoginResponse admin) {
        ResponseEntity<List<CostBasisAnomalyResponse>> response = restTemplate.exchange(
                "/api/stock/cost-basis-anomalies",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(admin)),
                new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private void assertNoUuid(String message) {
        assertThat(UUID_ANYWHERE.matcher(message).find())
                .as("user-visible string must contain no id: %s", message)
                .isFalse();
    }

    private void stockIn(
            TenantLoginResponse admin, UUID productId, int quantity, BigDecimal unitPrice, String unit, UUID vendorId) {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/products/" + productId + "/stock/stock-in",
                HttpMethod.POST,
                new HttpEntity<>(
                        new StockInRequest(quantity, unitPrice, null, unit, vendorId, null, null), authHeaders(admin)),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ProductResponse createRice(TenantLoginResponse admin, String sku, BigDecimal sellingPrice) {
        CreateProductRequest request = new CreateProductRequest(
                "Rice 50kg", sku, null, sellingPrice, null, "KG", "BAG", new BigDecimal("50"), null);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders productPartHeaders = new HttpHeaders();
        productPartHeaders.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(request, productPartHeaders));
        HttpHeaders headers = authHeaders(admin);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<ProductResponse> response = restTemplate.exchange(
                "/api/products", HttpMethod.POST, new HttpEntity<>(body, headers), ProductResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private CompanyVendorResponse createSupplier(TenantLoginResponse admin, String name) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<CompanyVendorResponse> response = restTemplate.exchange(
                "/api/company-vendors",
                HttpMethod.POST,
                new HttpEntity<>(
                        new CompanyVendorRequest(name, "0800" + suffix, null, null, null, null, null, null, null, null, null, null),
                        authHeaders(admin)),
                CompanyVendorResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private TenantLoginResponse signup(String name) {
        String unique = UUID.randomUUID().toString();
        ClientSignupRequest request = new ClientSignupRequest(
                name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD);
        return restTemplate.postForObject("/api/clients/signup", request, TenantLoginResponse.class);
    }

    private HttpHeaders authHeaders(TenantLoginResponse response) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(response.tokens().accessToken());
        return headers;
    }
}
