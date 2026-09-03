package com.procurepal_services.stock_bridge_api.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorResponse;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.ProductVendorResponse;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOption;
import com.procurepal_services.stock_bridge_api.stock.dto.InsufficientStockErrorResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.ProductLotResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockInRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMutationResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockOutRequest;
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
 * End-to-end coverage of the V21 unit/pack/price remediation - UNIT_UX_CONTRACT.md sections 2, 3
 * and 4 - over the real HTTP and Spring Security stack. Companion to
 * {@link MultiVendorInventoryIntegrationTest}, which owns the V19 costing and FIFO behaviour this
 * builds on; the self-contained-helpers-per-class convention is that class's, mirrored here.
 *
 * <p>The product used throughout is the one from the complaint: <b>Rice, counted in kg, packed
 * 50 kg to the bag.</b> Every assertion below is about what happens when somebody types "20 bags
 * at N45,000 per bag" into a system whose ledger is denominated in kilograms.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class UnitAndPriceNormalisationIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    /** Any 8-4-4-4-12 hex group. Non-negotiable 6 forbids one in any user-visible string. */
    private static final Pattern UUID_ANYWHERE =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Autowired
    private TestRestTemplate restTemplate;

    // ===================================================================== section 3.1 and 3.2

    /**
     * P0-1, the load-bearing defect, asserted end to end. "20 bags at N45,000/bag" on a KG/BAG/50
     * product must reach the ledger as 1,000 kg at N900/kg - in the movement, in the catalog cost
     * price and in the supplier's last cost - and must NOT reach it as N45,000 per kg, which is
     * what every one of those three said before this change.
     */
    @Test
    void twentyBagsAtFortyFiveThousandPerBagIsRecordedAsOneThousandKgAtNineHundredPerKg() {
        TenantLoginResponse admin = signup("Price Normalisation Co");
        CompanyVendorResponse supplier = createSupplier(admin, "Dangote Nigeria Plc");
        ProductResponse product = createRiceProduct(admin, "UPN-1");

        StockMutationResponse receipt = stockIn(
                        admin, product.id(), 20, new BigDecimal("45000"), "BAG", supplier.id(), null, null, null)
                .getBody();
        assertThat(receipt).isNotNull();

        // Quantity: converted, as it always was.
        assertThat(receipt.movement().quantity()).isEqualTo(1000);
        assertThat(receipt.product().quantityOnHand()).isEqualTo(1000);

        // Price: converted too, which is new. N45,000 / 50 = N900 per kg.
        assertThat(receipt.movement().unitPriceAtTime()).isEqualByComparingTo("900");
        assertThat(receipt.product().costPrice()).isEqualByComparingTo("900");
        assertThat(findSupplierLine(admin, product.id(), supplier.id()).lastCostPrice())
                .isEqualByComparingTo("900");
    }

    /**
     * The weighted average is only meaningful while every price entering it shares one basis.
     * Two deliveries of the same rice, one entered in bags and one in kg, must blend as though
     * they had both been entered in kg - which is the whole point of normalising at the door.
     */
    @Test
    void deliveriesEnteredInDifferentUnitsBlendIntoOneWeightedAverageCost() {
        TenantLoginResponse admin = signup("Mixed Entry Co");
        CompanyVendorResponse supplier = createSupplier(admin, "Dangote Nigeria Plc");
        ProductResponse product = createRiceProduct(admin, "UPN-2");

        // 1,000 kg at N900/kg, typed as 20 bags at N45,000.
        stockIn(admin, product.id(), 20, new BigDecimal("45000"), "BAG", supplier.id(), null, null, null);
        // 1,000 kg at N1,100/kg, typed in kg.
        StockMutationResponse second = stockIn(
                        admin, product.id(), 1000, new BigDecimal("1100"), "KG", supplier.id(), null, null, null)
                .getBody();

        // (1000*900 + 1000*1100) / 2000 = 1000.00 per kg.
        assertThat(second.product().costPrice()).isEqualByComparingTo("1000.00");
        assertThat(second.product().quantityOnHand()).isEqualTo(2000);
    }

    /**
     * Contract non-negotiable 8, the rule that keeps every existing API client and the existing
     * test suite green: a request that omits {@code unit} must behave EXACTLY as before - no
     * conversion of either half, and nothing written to the display-only entry columns.
     */
    @Test
    void omittingUnitBehavesExactlyAsItDidBeforeTheUnitSetExisted() {
        TenantLoginResponse admin = signup("Unchanged Caller Co");
        CompanyVendorResponse supplier = createSupplier(admin, "Dangote Nigeria Plc");
        ProductResponse product = createRiceProduct(admin, "UPN-3");

        StockMutationResponse receipt =
                stockIn(admin, product.id(), 500, new BigDecimal("2.50"), null, supplier.id(), null, null, null)
                        .getBody();

        assertThat(receipt.movement().quantity()).isEqualTo(500);
        assertThat(receipt.movement().unitPriceAtTime()).isEqualByComparingTo("2.50");
        assertThat(receipt.product().costPrice()).isEqualByComparingTo("2.50");
        assertThat(receipt.product().quantityOnHand()).isEqualTo(500);
    }

    /**
     * Section 3.1's unknown-unit 400. The message is the deliverable, not the status code: it
     * must name the product, list every unit that WOULD have worked, echo the one that did not,
     * and contain no id and no column name.
     */
    @Test
    void anUnknownUnitIsRejectedWithAMessageNamingEveryValidOption() {
        TenantLoginResponse admin = signup("Unknown Unit Co");
        CompanyVendorResponse supplier = createSupplier(admin, "Dangote Nigeria Plc");
        ProductResponse product = createRiceProduct(admin, "UPN-4");

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/products/" + product.id() + "/stock/stock-in",
                HttpMethod.POST,
                new HttpEntity<>(
                        new StockInRequest(5, new BigDecimal("1000"), null, "CARTON", supplier.id(), null, null),
                        authHeaders(admin)),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        String message = response.getBody().message();

        assertThat(message)
                .contains("Rice 50kg")
                .contains("is counted in")
                .contains("kg")
                .contains("bags of 50 kg")
                .contains("cartons");
        // The old message said only that CARTON was wrong. Naming the alternatives is the fix.
        assertThat(message).doesNotContain("CARTON").doesNotContain("unit_of_measure").doesNotContain("packaging");
        assertNoUuid(message);
    }

    /**
     * Section 3.1's round-to-zero 400. 1 g of a product counted in kg is 0.001 kg, which rounds
     * to nothing; recording that would mean writing "this delivery contained nothing" for a
     * delivery somebody just typed.
     */
    @Test
    void aConversionThatRoundsToZeroIsRejectedRatherThanSilentlyRecordedAsNothing() {
        TenantLoginResponse admin = signup("Round To Zero Co");
        CompanyVendorResponse supplier = createSupplier(admin, "Dangote Nigeria Plc");
        ProductResponse product = createRiceProduct(admin, "UPN-5");

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/products/" + product.id() + "/stock/stock-in",
                HttpMethod.POST,
                new HttpEntity<>(
                        new StockInRequest(1, new BigDecimal("5"), null, "G", supplier.id(), null, null),
                        authHeaders(admin)),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message())
                .isEqualTo("1 g is less than one whole kg — enter this in g by changing this product's stock "
                        + "unit, or enter a larger amount.");

        // Nothing was written: not a zero-quantity movement, not a cost.
        ResponseEntity<ProductResponse> unchanged = restTemplate.exchange(
                "/api/products/" + product.id(),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(admin)),
                ProductResponse.class);
        assertThat(unchanged.getBody().quantityOnHand()).isZero();
    }

    /**
     * P1-7 closed: a same-category base unit with a static factor is now a legitimate answer.
     * Before this, a KG product could not accept a delivery expressed in tonnes even though the
     * picker grouped them together and offered "Metric Ton (t)".
     */
    @Test
    void aSameCategoryBaseUnitIsAcceptedAndConvertedByItsStaticFactor() {
        TenantLoginResponse admin = signup("Tonne Delivery Co");
        CompanyVendorResponse supplier = createSupplier(admin, "Dangote Nigeria Plc");
        ProductResponse product = createRiceProduct(admin, "UPN-6");

        StockMutationResponse receipt = stockIn(
                        admin, product.id(), 2, new BigDecimal("900000"), "T", supplier.id(), null, null, null)
                .getBody();

        assertThat(receipt.movement().quantity()).isEqualTo(2000);
        assertThat(receipt.movement().unitPriceAtTime()).isEqualByComparingTo("900");
    }

    // ============================================================================= section 3.4

    /**
     * P0-5, and contract non-negotiable 7. The stock-in modal promised, in as many words, that
     * "the vendor's default stays unchanged" while the service overwrote it from the same
     * request fields. Without the opt-in, a one-off 25 kg-bag delivery must leave the supplier's
     * standing 50 kg pack exactly as it was.
     */
    @Test
    void aPerDeliveryPackDoesNotTouchTheSuppliersDefaultUnlessAskedTo() {
        TenantLoginResponse admin = signup("Pack Override Co");
        CompanyVendorResponse supplier = createSupplier(admin, "Dangote Nigeria Plc");
        ProductResponse product = createRiceProduct(admin, "UPN-7");

        // Establish the supplier's standing pack the way the product form does.
        stockIn(admin, product.id(), 10, new BigDecimal("45000"), "BAG", supplier.id(), "BAG", new BigDecimal("50"), true);
        ProductVendorResponse before = findSupplierLine(admin, product.id(), supplier.id());
        assertThat(before.defaultPackagingUnit()).isEqualTo("BAG");
        assertThat(before.defaultPackagingSize()).isEqualByComparingTo("50");

        // One delivery arrives in 25 kg bags, with no opt-in. The override applies to THIS
        // delivery and to nothing else.
        StockMutationResponse oneOff = stockIn(
                        admin,
                        product.id(),
                        4,
                        new BigDecimal("22500"),
                        "BAG",
                        supplier.id(),
                        "BAG",
                        new BigDecimal("25"),
                        null)
                .getBody();
        assertThat(oneOff.movement().quantity()).isEqualTo(100);
        assertThat(oneOff.movement().unitPriceAtTime()).isEqualByComparingTo("900");

        ProductVendorResponse after = findSupplierLine(admin, product.id(), supplier.id());
        assertThat(after.defaultPackagingUnit()).isEqualTo("BAG");
        assertThat(after.defaultPackagingSize()).isEqualByComparingTo("50");

        // lastCostPrice is a running fact, not configuration - it updates on every priced
        // receipt regardless of the flag (section 3.4 draws the line there explicitly).
        assertThat(after.lastCostPrice()).isEqualByComparingTo("900");
    }

    /** The same override WITH the opt-in does change the standing default - the checkbox works. */
    @Test
    void thePerDeliveryPackBecomesTheSuppliersDefaultWhenExplicitlyAskedTo() {
        TenantLoginResponse admin = signup("Pack Opt In Co");
        CompanyVendorResponse supplier = createSupplier(admin, "Dangote Nigeria Plc");
        ProductResponse product = createRiceProduct(admin, "UPN-8");

        stockIn(admin, product.id(), 10, new BigDecimal("45000"), "BAG", supplier.id(), "BAG", new BigDecimal("50"), true);
        stockIn(admin, product.id(), 4, new BigDecimal("22500"), "BAG", supplier.id(), "BAG", new BigDecimal("25"), true);

        ProductVendorResponse after = findSupplierLine(admin, product.id(), supplier.id());
        assertThat(after.defaultPackagingSize()).isEqualByComparingTo("25");
    }

    // =============================================================================== section 2

    /**
     * The wire shape three downstream modules render from. Steps 1, 2 and 4 on the product; the
     * supplier's own pack additionally on its vendor line (step 3).
     */
    @Test
    void unitOptionsArePublishedOnTheProductAndOnEachSupplierLine() {
        TenantLoginResponse admin = signup("Unit Options Co");
        CompanyVendorResponse supplier = createSupplier(admin, "Dangote Nigeria Plc");
        ProductResponse product = createRiceProduct(admin, "UPN-9");

        assertThat(product.unitOptions()).extracting(UnitOption::code).containsExactly("KG", "BAG", "MG", "G", "T");
        UnitOption bag = product.unitOptions().get(1);
        assertThat(bag.label()).isEqualTo("Bag of 50 kg");
        assertThat(bag.factorToStockUnit()).isEqualByComparingTo("50");
        assertThat(bag.isDefault()).isTrue();
        assertThat(product.unitOptions().stream().filter(UnitOption::isDefault)).hasSize(1);

        // The supplier delivers in cartons of 12; its line offers that as a fourth way to count.
        stockIn(admin, product.id(), 1, new BigDecimal("10800"), "CARTON", supplier.id(), "CARTON", new BigDecimal("12"), true);
        ProductVendorResponse line = findSupplierLine(admin, product.id(), supplier.id());
        assertThat(line.unitOptions()).extracting(UnitOption::code).contains("KG", "BAG", "CARTON");
        assertThat(line.unitOptions().stream().filter(o -> "CARTON".equals(o.code())).findFirst().orElseThrow().label())
                .isEqualTo("Carton of 12 kg");
    }

    // =============================================================================== section 4

    /**
     * The lot endpoint's whole reason to exist: {@code remaining}, which no page of movement
     * history carries. Two deliveries, a sale that eats the first and part of the second, and the
     * picker must then show one open lot with the right balance - not two, and never a negative.
     */
    @Test
    void theLotEndpointReportsRemainingPerDeliveryAndHidesConsumedOnes() {
        TenantLoginResponse admin = signup("Open Lots Co");
        CompanyVendorResponse supplier = createSupplier(admin, "Dangote Nigeria Plc");
        ProductResponse product = createRiceProduct(admin, "UPN-10");

        stockIn(admin, product.id(), 6, new BigDecimal("45000"), "BAG", supplier.id(), null, null, null); // 300 kg
        stockIn(admin, product.id(), 4, new BigDecimal("50000"), "BAG", supplier.id(), null, null, null); // 200 kg

        List<ProductLotResponse> beforeSale = lots(admin, product.id(), true);
        assertThat(beforeSale).hasSize(2);
        assertThat(beforeSale).extracting(ProductLotResponse::quantity).containsExactly(300, 200);
        assertThat(beforeSale).extracting(ProductLotResponse::remaining).containsExactly(300, 200);
        // Per stock unit, both of them: N45,000/bag is N900/kg, N50,000/bag is N1,000/kg.
        assertThat(beforeSale.get(0).unitPriceAtTime()).isEqualByComparingTo("900");
        assertThat(beforeSale.get(1).unitPriceAtTime()).isEqualByComparingTo("1000");

        // The label is server-composed and names the delivery the way a person would.
        assertThat(beforeSale.get(0).label())
                .contains("Dangote Nigeria Plc")
                .contains("·");
        assertNoUuid(beforeSale.get(0).label());
        assertThat(beforeSale.get(0).companyVendorName()).isEqualTo(supplier.name());

        // Take out 350 kg: all of lot one, 50 kg of lot two.
        restTemplate.exchange(
                "/api/products/" + product.id() + "/stock/stock-out",
                HttpMethod.POST,
                new HttpEntity<>(new StockOutRequest(350, null, "sale"), authHeaders(admin)),
                StockMutationResponse.class);

        List<ProductLotResponse> open = lots(admin, product.id(), true);
        assertThat(open).hasSize(1);
        assertThat(open.get(0).inMovementId()).isEqualTo(beforeSale.get(1).inMovementId());
        assertThat(open.get(0).quantity()).isEqualTo(200);
        assertThat(open.get(0).remaining()).isEqualTo(150);

        // open=false still shows the exhausted lot, at zero and never below it.
        List<ProductLotResponse> all = lots(admin, product.id(), false);
        assertThat(all).hasSize(2);
        assertThat(all.get(0).remaining()).isZero();
        assertThat(all).allSatisfy(lot -> assertThat(lot.remaining()).isGreaterThanOrEqualTo(0));
    }

    /**
     * P1-6: an allocation refusal names the delivery by its date and supplier - the phrase on the
     * row the user clicked - and never by the id, which is not on their screen and which
     * non-negotiable 6 forbids in a user-visible string. Both quantities state their unit, which
     * is what makes the P1-4 mismatch legible instead of baffling.
     */
    @Test
    void anOverdrawnAllocationNamesTheDeliveryByDateAndSupplierWithNoIdInSight() {
        TenantLoginResponse admin = signup("Allocation Copy Co");
        CompanyVendorResponse supplier = createSupplier(admin, "Dangote Nigeria Plc");
        ProductResponse product = createRiceProduct(admin, "UPN-11");

        stockIn(admin, product.id(), 1, new BigDecimal("45000"), "BAG", supplier.id(), null, null, null); // 50 kg
        stockIn(admin, product.id(), 2, new BigDecimal("45000"), "BAG", supplier.id(), null, null, null); // 100 kg
        UUID firstLot = lots(admin, product.id(), true).get(0).inMovementId();

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/products/" + product.id() + "/stock/stock-out",
                HttpMethod.POST,
                new HttpEntity<>(
                        new StockOutRequest(
                                80, null, null, null, List.of(new StockOutRequest.Allocation(firstLot, 80))),
                        authHeaders(admin)),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        String message = response.getBody().message();
        assertThat(message)
                .contains("Only 50 kg left")
                .contains("delivery from Dangote Nigeria Plc")
                .contains("you asked for 80 kg");
        assertNoUuid(message);
    }

    /** The same rule for the sum-mismatch refusal, which used to name neither unit. */
    @Test
    void anAllocationTotalMismatchStatesTheUnitOfBothNumbers() {
        TenantLoginResponse admin = signup("Allocation Total Co");
        CompanyVendorResponse supplier = createSupplier(admin, "Dangote Nigeria Plc");
        ProductResponse product = createRiceProduct(admin, "UPN-12");

        stockIn(admin, product.id(), 4, new BigDecimal("45000"), "BAG", supplier.id(), null, null, null); // 200 kg
        UUID lot = lots(admin, product.id(), true).get(0).inMovementId();

        // Toggle to bags, ask for 2 bags (100 kg), allocate 50 kg. The old message was
        // "allocations sum to 50 but the requested quantity is 100" with no unit on either.
        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/products/" + product.id() + "/stock/stock-out",
                HttpMethod.POST,
                new HttpEntity<>(
                        new StockOutRequest(2, null, null, "BAG", List.of(new StockOutRequest.Allocation(lot, 50))),
                        authHeaders(admin)),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message())
                .contains("add up to 50 kg")
                .contains("taking out 100 kg");
        assertNoUuid(response.getBody().message());
    }

    /** The 409 oversell body states its unit on both figures too (non-negotiable 2). */
    @Test
    void theOversellRefusalStatesItsUnit() {
        TenantLoginResponse admin = signup("Oversell Copy Co");
        CompanyVendorResponse supplier = createSupplier(admin, "Dangote Nigeria Plc");
        ProductResponse product = createRiceProduct(admin, "UPN-13");

        stockIn(admin, product.id(), 1, new BigDecimal("45000"), "BAG", supplier.id(), null, null, null); // 50 kg

        ResponseEntity<InsufficientStockErrorResponse> response = restTemplate.exchange(
                "/api/products/" + product.id() + "/stock/stock-out",
                HttpMethod.POST,
                new HttpEntity<>(new StockOutRequest(2, null, null, "BAG", null), authHeaders(admin)),
                InsufficientStockErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().message()).isEqualTo("Only 50 kg available — you asked for 100 kg.");
        // The raw numbers stay on the body, both in the product's stock unit - a client that
        // wants to render its own sentence never has to parse the one above.
        assertThat(response.getBody().availableQuantity()).isEqualTo(50);
        assertThat(response.getBody().requestedQuantity()).isEqualTo(100);
    }

    /**
     * The stock-out receipt carries a composed label per lot, so the UI can show the breakdown by
     * supplier AND date without string-building one (contract section 4's "server-composed").
     */
    @Test
    void theStockOutBreakdownCarriesAComposedLabelPerLot() {
        TenantLoginResponse admin = signup("Breakdown Label Co");
        CompanyVendorResponse supplier = createSupplier(admin, "Dangote Nigeria Plc");
        ProductResponse product = createRiceProduct(admin, "UPN-14");

        stockIn(admin, product.id(), 2, new BigDecimal("45000"), "BAG", supplier.id(), null, null, null);

        StockMutationResponse out = restTemplate
                .exchange(
                        "/api/products/" + product.id() + "/stock/stock-out",
                        HttpMethod.POST,
                        new HttpEntity<>(new StockOutRequest(1, null, null, "BAG", null), authHeaders(admin)),
                        StockMutationResponse.class)
                .getBody();

        assertThat(out.breakdown()).hasSize(1);
        StockMutationResponse.AllocationBreakdown line = out.breakdown().get(0);
        assertThat(line.quantity()).isEqualTo(50);
        assertThat(line.label()).contains("Dangote Nigeria Plc").contains("·");
        assertNoUuid(line.label());
    }

    // -----------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------

    private void assertNoUuid(String message) {
        assertThat(UUID_ANYWHERE.matcher(message).find())
                .as("user-visible string must contain no id: %s", message)
                .isFalse();
    }

    private List<ProductLotResponse> lots(TenantLoginResponse admin, UUID productId, boolean open) {
        ResponseEntity<List<ProductLotResponse>> response = restTemplate.exchange(
                "/api/products/" + productId + "/lots?open=" + open,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(admin)),
                new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<StockMutationResponse> stockIn(
            TenantLoginResponse admin,
            UUID productId,
            int quantity,
            BigDecimal unitPrice,
            String unit,
            UUID companyVendorId,
            String packagingUnit,
            BigDecimal packagingSize,
            Boolean saveAsSupplierDefault) {
        ResponseEntity<StockMutationResponse> response = restTemplate.exchange(
                "/api/products/" + productId + "/stock/stock-in",
                HttpMethod.POST,
                new HttpEntity<>(
                        new StockInRequest(
                                quantity,
                                unitPrice,
                                null,
                                unit,
                                companyVendorId,
                                packagingUnit,
                                packagingSize,
                                null,
                                saveAsSupplierDefault),
                        authHeaders(admin)),
                StockMutationResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response;
    }

    private ProductVendorResponse findSupplierLine(TenantLoginResponse admin, UUID productId, UUID companyVendorId) {
        ResponseEntity<List<ProductVendorResponse>> response = restTemplate.exchange(
                "/api/products/" + productId + "/vendors",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(admin)),
                new ParameterizedTypeReference<>() {});
        return response.getBody().stream()
                .filter(line -> line.companyVendorId().equals(companyVendorId))
                .findFirst()
                .orElseThrow();
    }

    /** Rice, counted in kg, packed 50 kg to the bag - the product from the complaint. */
    private ProductResponse createRiceProduct(TenantLoginResponse admin, String sku) {
        CreateProductRequest request = new CreateProductRequest(
                "Rice 50kg", sku, null, null, null, "KG", "BAG", new BigDecimal("50"), null);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders productPartHeaders = new HttpHeaders();
        productPartHeaders.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(request, productPartHeaders));
        HttpHeaders headers = authHeaders(admin);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<ProductResponse> response =
                restTemplate.exchange("/api/products", HttpMethod.POST, new HttpEntity<>(body, headers), ProductResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private CompanyVendorResponse createSupplier(TenantLoginResponse admin, String name) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<CompanyVendorResponse> response = restTemplate.exchange(
                "/api/company-vendors",
                HttpMethod.POST,
                new HttpEntity<>(
                        new CompanyVendorRequest(name, "0800" + suffix, null, null, null, null, null, null),
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
