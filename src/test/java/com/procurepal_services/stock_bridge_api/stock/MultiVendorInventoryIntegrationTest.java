package com.procurepal_services.stock_bridge_api.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.AddPriceTierRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorResponse;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.ProductVendorPriceTierResponse;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.ProductVendorResponse;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.UpdateProductVendorRequest;
import com.procurepal_services.stock_bridge_api.entity.MovementType;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.AllocationResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.InsufficientStockErrorResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockInRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMovementResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMutationResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockOutRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * End-to-end coverage of the V19 multi-vendor inventory feature - ProductVendor lines,
 * price tiers, weighted-average costing, lot-level FIFO stock-out, the 409 oversell mapping and
 * the allocation-trace endpoint. Exercises the real HTTP + Spring Security filter chain against
 * the local docker-compose Postgres - see StockManagementIntegrationTest for why local Postgres
 * over Testcontainers, and for the self-contained-helpers-per-class convention this mirrors.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class MultiVendorInventoryIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void creatingProductWithInitialVendorProducesPreferredVendorLineOpeningMovementAndCorrectCostAndQuantity() {
        TenantLoginResponse admin = signup("Initial Vendor Co");
        CompanyVendorResponse vendorA = createCompanyVendor(admin, "Vendor A");

        ProductResponse product = createProductWithInitialVendor(admin, "MV-1", vendorA.id(), new BigDecimal("100.00"), 50);

        assertThat(product.quantityOnHand()).isEqualByComparingTo("50");
        assertThat(product.costPrice()).isEqualByComparingTo("100.00");
        assertThat(product.preferredVendorName()).isEqualTo(vendorA.name());

        List<ProductVendorResponse> vendors = listVendors(admin, product.id());
        assertThat(vendors).hasSize(1);
        ProductVendorResponse line = vendors.get(0);
        assertThat(line.companyVendorId()).isEqualTo(vendorA.id());
        assertThat(line.isPreferred()).isTrue();
        assertThat(line.lastCostPrice()).isEqualByComparingTo("100.00");
        assertThat(line.quantityOnHandFromVendor()).isEqualByComparingTo("50");
        assertThat(line.totalQuantityReceived()).isEqualByComparingTo("50");
        assertThat(line.priceTiers()).isEmpty();

        List<StockMovementResponse> history = history(admin, product.id());
        assertThat(history).hasSize(1);
        StockMovementResponse opening = history.get(0);
        assertThat(opening.movementType()).isEqualTo(MovementType.IN);
        assertThat(opening.quantity()).isEqualByComparingTo("50");
        assertThat(opening.unitPriceAtTime()).isEqualByComparingTo("100.00");
        assertThat(opening.companyVendorId()).isEqualTo(vendorA.id());
        assertThat(opening.companyVendorName()).isEqualTo(vendorA.name());
    }

    @Test
    void secondStockInFromDifferentVendorRecomputesWeightedAverageCost() {
        TenantLoginResponse admin = signup("Weighted Avg Co");
        CompanyVendorResponse vendorA = createCompanyVendor(admin, "Vendor A");
        CompanyVendorResponse vendorB = createCompanyVendor(admin, "Vendor B");

        ProductResponse product = createProductWithInitialVendor(admin, "MV-2", vendorA.id(), new BigDecimal("100.00"), 50);
        assertThat(product.costPrice()).isEqualByComparingTo("100.00");

        StockMutationResponse afterSecondStockIn =
                stockIn(admin, product.id(), 50, new BigDecimal("200.00"), vendorB.id()).getBody();

        // (50*100 + 50*200) / 100 = 150.00
        assertThat(afterSecondStockIn.product().costPrice()).isEqualByComparingTo("150.00");
        assertThat(afterSecondStockIn.product().quantityOnHand()).isEqualByComparingTo("100");
        assertThat(afterSecondStockIn.vendorIsNewToProduct()).isTrue();

        List<ProductVendorResponse> vendors = listVendors(admin, product.id());
        assertThat(vendors).hasSize(2);
    }

    @Test
    void preferredVendorSwapAtomicallyUnflipsThePreviousPreferredVendor() {
        TenantLoginResponse admin = signup("Preferred Swap Co");
        CompanyVendorResponse vendorA = createCompanyVendor(admin, "Vendor A");
        CompanyVendorResponse vendorB = createCompanyVendor(admin, "Vendor B");

        ProductResponse product = createProductWithInitialVendor(admin, "MV-3", vendorA.id(), new BigDecimal("50.00"), 10);
        stockIn(admin, product.id(), 10, new BigDecimal("55.00"), vendorB.id());

        ProductVendorResponse lineA = findVendorLine(admin, product.id(), vendorA.id());
        ProductVendorResponse lineB = findVendorLine(admin, product.id(), vendorB.id());
        assertThat(lineA.isPreferred()).isTrue();
        assertThat(lineB.isPreferred()).isFalse();

        ResponseEntity<ProductVendorResponse> swapResponse = restTemplate.exchange(
                "/api/products/" + product.id() + "/vendors/" + lineB.id(),
                HttpMethod.PATCH,
                new HttpEntity<>(new UpdateProductVendorRequest(null, null, null, true), authHeaders(admin)),
                ProductVendorResponse.class);
        assertThat(swapResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(swapResponse.getBody().isPreferred()).isTrue();

        // One call, no constraint violation, and exactly one row holds isPreferred afterward.
        List<ProductVendorResponse> vendorsAfterSwap = listVendors(admin, product.id());
        assertThat(vendorsAfterSwap.stream().filter(ProductVendorResponse::isPreferred)).hasSize(1);
        assertThat(findVendorLine(admin, product.id(), vendorA.id()).isPreferred()).isFalse();
        assertThat(findVendorLine(admin, product.id(), vendorB.id()).isPreferred()).isTrue();
    }

    @Test
    void stockOutWithNoAllocationsConsumesOldestLotsFirstAndTracesForward() {
        TenantLoginResponse admin = signup("FIFO Co");
        CompanyVendorResponse vendorA = createCompanyVendor(admin, "Vendor A");
        CompanyVendorResponse vendorB = createCompanyVendor(admin, "Vendor B");

        ProductResponse product = createProductWithInitialVendor(admin, "MV-4", vendorA.id(), new BigDecimal("100.00"), 30);
        stockIn(admin, product.id(), 20, new BigDecimal("110.00"), vendorB.id());

        List<StockMovementResponse> lots = history(admin, product.id());
        StockMovementResponse lotA = lots.stream().filter(m -> vendorA.id().equals(m.companyVendorId())).findFirst().orElseThrow();
        StockMovementResponse lotB = lots.stream().filter(m -> vendorB.id().equals(m.companyVendorId())).findFirst().orElseThrow();

        ResponseEntity<StockMutationResponse> outResponse = restTemplate.exchange(
                "/api/products/" + product.id() + "/stock/stock-out",
                HttpMethod.POST,
                new HttpEntity<>(new StockOutRequest(BigDecimal.valueOf(40), null, "sale"), authHeaders(admin)),
                StockMutationResponse.class);
        assertThat(outResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        StockMutationResponse body = outResponse.getBody();
        assertThat(body.product().quantityOnHand()).isEqualByComparingTo("10");

        List<StockMutationResponse.AllocationBreakdown> breakdown = body.breakdown();
        assertThat(breakdown).hasSize(2);
        assertThat(breakdown.get(0).inMovementId()).isEqualTo(lotA.id());
        assertThat(breakdown.get(0).quantity()).isEqualByComparingTo("30");
        assertThat(breakdown.get(0).companyVendorId()).isEqualTo(vendorA.id());
        assertThat(breakdown.get(1).inMovementId()).isEqualTo(lotB.id());
        assertThat(breakdown.get(1).quantity()).isEqualByComparingTo("10");
        assertThat(breakdown.get(1).companyVendorId()).isEqualTo(vendorB.id());

        // Cached per-vendor rollups reflect the drawdown.
        assertThat(findVendorLine(admin, product.id(), vendorA.id()).quantityOnHandFromVendor()).isEqualByComparingTo("0");
        assertThat(findVendorLine(admin, product.id(), vendorB.id()).quantityOnHandFromVendor()).isEqualByComparingTo("10");

        // Forward trace: lot A's delivery funded exactly this one sale, for 30 units.
        List<AllocationResponse> allocationsForLotA = allocations(admin, lotA.id());
        assertThat(allocationsForLotA).hasSize(1);
        assertThat(allocationsForLotA.get(0).outMovementId()).isEqualTo(body.movement().id());
        assertThat(allocationsForLotA.get(0).quantity()).isEqualByComparingTo("30");

        List<AllocationResponse> allocationsForLotB = allocations(admin, lotB.id());
        assertThat(allocationsForLotB).hasSize(1);
        assertThat(allocationsForLotB.get(0).outMovementId()).isEqualTo(body.movement().id());
        assertThat(allocationsForLotB.get(0).quantity()).isEqualByComparingTo("10");
    }

    @Test
    void concurrentStockOutRequestsAgainstTheSameLotDoNotCauseNegativeQuantityOrLostUpdates() throws InterruptedException {
        TenantLoginResponse admin = signup("Lot Concurrency Co");
        CompanyVendorResponse vendorA = createCompanyVendor(admin, "Vendor A");
        ProductResponse product = createProductWithInitialVendor(admin, "MV-5", vendorA.id(), new BigDecimal("100.00"), 10);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<String>>> futures = List.of(
                    executor.submit(() -> stockOutRaw(admin, product.id(), 7, startLatch)),
                    executor.submit(() -> stockOutRaw(admin, product.id(), 7, startLatch)));
            startLatch.countDown();

            long successCount = 0;
            long rejectedCount = 0;
            for (Future<ResponseEntity<String>> future : futures) {
                HttpStatus status = (HttpStatus) future.get(10, TimeUnit.SECONDS).getStatusCode();
                if (status == HttpStatus.OK) {
                    successCount++;
                } else if (status == HttpStatus.CONFLICT) {
                    rejectedCount++;
                }
            }

            assertThat(successCount).isEqualTo(1);
            assertThat(rejectedCount).isEqualTo(1);

            ResponseEntity<ProductResponse> finalState = restTemplate.exchange(
                    "/api/products/" + product.id(), HttpMethod.GET, new HttpEntity<>(authHeaders(admin)), ProductResponse.class);
            assertThat(finalState.getBody().quantityOnHand()).isEqualByComparingTo("3");

            // No lost update at the lot-allocation layer either: exactly 7 units were allocated
            // in total against the single lot, never 14 and never 0.
            List<StockMovementResponse> lots = history(admin, product.id()).stream()
                    .filter(m -> m.movementType() == MovementType.IN)
                    .toList();
            assertThat(lots).hasSize(1);
            List<AllocationResponse> allocationsForTheLot = allocations(admin, lots.get(0).id());
            int totalAllocated = allocationsForTheLot.stream().mapToInt(a -> a.quantity().intValueExact()).sum();
            assertThat(totalAllocated).isEqualTo(7);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void oversellIsRejectedWith409AndTheActualAvailableQuantity() {
        TenantLoginResponse admin = signup("Oversell Co");
        CompanyVendorResponse vendorA = createCompanyVendor(admin, "Vendor A");
        ProductResponse product = createProductWithInitialVendor(admin, "MV-6", vendorA.id(), new BigDecimal("100.00"), 5);

        ResponseEntity<InsufficientStockErrorResponse> response = restTemplate.exchange(
                "/api/products/" + product.id() + "/stock/stock-out",
                HttpMethod.POST,
                new HttpEntity<>(new StockOutRequest(BigDecimal.valueOf(10), null, null), authHeaders(admin)),
                InsufficientStockErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        InsufficientStockErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(409);
        assertThat(body.availableQuantity()).isEqualByComparingTo("5");
        assertThat(body.requestedQuantity()).isEqualByComparingTo("10");
        assertThat(body.message()).contains("5").contains("10");

        // Never went negative.
        ResponseEntity<ProductResponse> unchanged = restTemplate.exchange(
                "/api/products/" + product.id(), HttpMethod.GET, new HttpEntity<>(authHeaders(admin)), ProductResponse.class);
        assertThat(unchanged.getBody().quantityOnHand()).isEqualByComparingTo("5");
    }

    @Test
    void priceTiersCanBeAddedAndRemoved() {
        TenantLoginResponse admin = signup("Price Tier Co");
        CompanyVendorResponse vendorA = createCompanyVendor(admin, "Vendor A");
        ProductResponse product = createProductWithInitialVendor(admin, "MV-7", vendorA.id(), new BigDecimal("100.00"), 5);
        ProductVendorResponse line = findVendorLine(admin, product.id(), vendorA.id());
        // The initial vendor's receipt has no packaging, so it lands on the bare-stock-unit
        // default pack (MULTI_PACK_PER_VENDOR_DESIGN.md sections 4-6) - price tiers now hang off
        // a pack, not the vendor line directly (V24).
        UUID packId = line.packs().get(0).id();

        ResponseEntity<ProductVendorPriceTierResponse> addResponse = restTemplate.exchange(
                "/api/products/" + product.id() + "/vendors/" + line.id() + "/packs/" + packId + "/price-tiers",
                HttpMethod.POST,
                new HttpEntity<>(new AddPriceTierRequest(new BigDecimal("10"), new BigDecimal("90.00")), authHeaders(admin)),
                ProductVendorPriceTierResponse.class);
        assertThat(addResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID tierId = addResponse.getBody().id();

        assertThat(findVendorLine(admin, product.id(), vendorA.id()).priceTiers()).hasSize(1);

        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
                "/api/products/" + product.id() + "/vendors/" + line.id() + "/packs/" + packId + "/price-tiers/" + tierId,
                HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(admin)),
                Void.class);
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(findVendorLine(admin, product.id(), vendorA.id()).priceTiers()).isEmpty();
    }

    @Test
    void vendorPriceTierAndAllocationEndpointsAll404ForACallerFromAnotherTenant() {
        TenantLoginResponse tenantA = signup("Isolation Tenant A " + UUID.randomUUID());
        TenantLoginResponse tenantB = signup("Isolation Tenant B " + UUID.randomUUID());

        CompanyVendorResponse vendorA = createCompanyVendor(tenantA, "Vendor A");
        ProductResponse product = createProductWithInitialVendor(tenantA, "MV-ISO-1", vendorA.id(), new BigDecimal("100.00"), 10);
        ProductVendorResponse line = findVendorLine(tenantA, product.id(), vendorA.id());
        UUID packId = line.packs().get(0).id();
        UUID inMovementId = history(tenantA, product.id()).get(0).id();

        ResponseEntity<ApiError> listAsB = restTemplate.exchange(
                "/api/products/" + product.id() + "/vendors",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(tenantB)),
                ApiError.class);
        assertThat(listAsB.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<ApiError> patchAsB = restTemplate.exchange(
                "/api/products/" + product.id() + "/vendors/" + line.id(),
                HttpMethod.PATCH,
                new HttpEntity<>(new UpdateProductVendorRequest(null, null, null, true), authHeaders(tenantB)),
                ApiError.class);
        assertThat(patchAsB.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<ApiError> addTierAsB = restTemplate.exchange(
                "/api/products/" + product.id() + "/vendors/" + line.id() + "/packs/" + packId + "/price-tiers",
                HttpMethod.POST,
                new HttpEntity<>(new AddPriceTierRequest(new BigDecimal("10"), new BigDecimal("90.00")), authHeaders(tenantB)),
                ApiError.class);
        assertThat(addTierAsB.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<ApiError> deleteTierAsB = restTemplate.exchange(
                "/api/products/" + product.id() + "/vendors/" + line.id() + "/packs/" + packId + "/price-tiers/" + UUID.randomUUID(),
                HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(tenantB)),
                ApiError.class);
        assertThat(deleteTierAsB.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<ApiError> allocationsAsB = restTemplate.exchange(
                "/api/stock-movements/" + inMovementId + "/allocations",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(tenantB)),
                ApiError.class);
        assertThat(allocationsAsB.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // -----------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------

    @Test
    void aProductIsAddedWithSeveralSuppliersAndItsOpeningStockFromOneOfThemOrNone() {
        TenantLoginResponse admin = signup("Several Suppliers Co");
        CompanyVendorResponse mama = createCompanyVendor(admin, "Mama");
        CompanyVendorResponse alaba = createCompanyVendor(admin, "Alaba");
        CreateProductRequest request = new CreateProductRequest(
                "Rice MS", "MS-1", null, null, null, "KG", "BAG", new BigDecimal("80"), null, null, null, null, null, null,
                null,
                List.of(
                        new CreateProductRequest.SupplierLink(mama.id(), null, new BigDecimal("1000"), "BAG", new BigDecimal("80")),
                        new CreateProductRequest.SupplierLink(alaba.id(), null, null, "BAG", new BigDecimal("50"))),
                new CreateProductRequest.OpeningStock(new BigDecimal("160"), mama.id(), null));
        ResponseEntity<ProductResponse> created =
                restTemplate.exchange("/api/products", HttpMethod.POST, multipartEntity(admin, request), ProductResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ProductResponse product = created.getBody();
        // Both linked, the first preferred; the opening stock came from Mama, at her price.
        assertThat(product.quantityOnHand()).isEqualByComparingTo("160");
        assertThat(product.preferredVendorName()).isEqualTo(mama.name());
        assertThat(listVendors(admin, product.id())).extracting(ProductVendorResponse::companyVendorId)
                .containsExactlyInAnyOrder(mama.id(), alaba.id());
        assertThat(findVendorLine(admin, product.id(), alaba.id()).totalQuantityReceived()).isEqualByComparingTo("0");
        assertThat(findVendorLine(admin, product.id(), mama.id()).totalQuantityReceived()).isEqualByComparingTo("160");
        StockMovementResponse opening = history(admin, product.id()).get(0);
        assertThat(opening.unitPriceAtTime()).isEqualByComparingTo("1000");
        assertThat(opening.companyVendorId()).isEqualTo(mama.id());

        // Opening stock from nobody named, and no suppliers at all.
        CreateProductRequest bare = new CreateProductRequest(
                "Salt MS", "MS-2", null, null, null, "KG", null, null, null, null, null, null, null, null, null, null,
                new CreateProductRequest.OpeningStock(new BigDecimal("3.5"), null, null));
        ResponseEntity<ProductResponse> salt =
                restTemplate.exchange("/api/products", HttpMethod.POST, multipartEntity(admin, bare), ProductResponse.class);
        assertThat(salt.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(salt.getBody().quantityOnHand()).isEqualByComparingTo("3.5");
        assertThat(listVendors(admin, salt.getBody().id())).isEmpty();

        // A supplier linked later, from the edit page, with nothing received.
        CompanyVendorResponse third = createCompanyVendor(admin, "Third");
        ResponseEntity<ProductVendorResponse> linked = restTemplate.exchange(
                "/api/products/" + salt.getBody().id() + "/vendors", HttpMethod.POST,
                new HttpEntity<>(java.util.Map.of("companyVendorId", third.id(), "cost", 400), authHeaders(admin)),
                ProductVendorResponse.class);
        assertThat(linked.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(linked.getBody().isPreferred()).isTrue();
        assertThat(linked.getBody().totalQuantityReceived()).isEqualByComparingTo("0");
    }

    private ResponseEntity<String> stockOutRaw(TenantLoginResponse admin, UUID productId, int quantity, CountDownLatch startLatch) {
        try {
            startLatch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return restTemplate.exchange(
                "/api/products/" + productId + "/stock/stock-out",
                HttpMethod.POST,
                new HttpEntity<>(new StockOutRequest(BigDecimal.valueOf(quantity), null, null), authHeaders(admin)),
                String.class);
    }

    private ResponseEntity<StockMutationResponse> stockIn(
            TenantLoginResponse admin, UUID productId, int quantity, BigDecimal unitPrice, UUID companyVendorId) {
        return restTemplate.exchange(
                "/api/products/" + productId + "/stock/stock-in",
                HttpMethod.POST,
                new HttpEntity<>(new StockInRequest(quantity, unitPrice, null, null, companyVendorId, null, null), authHeaders(admin)),
                StockMutationResponse.class);
    }

    private List<AllocationResponse> allocations(TenantLoginResponse admin, UUID inMovementId) {
        ResponseEntity<List<AllocationResponse>> response = restTemplate.exchange(
                "/api/stock-movements/" + inMovementId + "/allocations",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(admin)),
                new ParameterizedTypeReference<>() {});
        return response.getBody();
    }

    private List<ProductVendorResponse> listVendors(TenantLoginResponse admin, UUID productId) {
        ResponseEntity<List<ProductVendorResponse>> response = restTemplate.exchange(
                "/api/products/" + productId + "/vendors",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(admin)),
                new ParameterizedTypeReference<>() {});
        return response.getBody();
    }

    private ProductVendorResponse findVendorLine(TenantLoginResponse admin, UUID productId, UUID companyVendorId) {
        return listVendors(admin, productId).stream()
                .filter(v -> v.companyVendorId().equals(companyVendorId))
                .findFirst()
                .orElseThrow();
    }

    private List<StockMovementResponse> history(TenantLoginResponse admin, UUID productId) {
        ResponseEntity<TestPage<StockMovementResponse>> response = restTemplate.exchange(
                "/api/products/" + productId + "/stock/history",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(admin)),
                new ParameterizedTypeReference<>() {});
        return response.getBody().content();
    }

    private CompanyVendorResponse createCompanyVendor(TenantLoginResponse admin, String namePrefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<CompanyVendorResponse> response = restTemplate.exchange(
                "/api/company-vendors",
                HttpMethod.POST,
                new HttpEntity<>(
                        new CompanyVendorRequest(namePrefix + " " + suffix, "0800" + suffix, null, null, null, null, null, null, null, null, null, null),
                        authHeaders(admin)),
                CompanyVendorResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private ProductResponse createProductWithInitialVendor(
            TenantLoginResponse admin, String sku, UUID companyVendorId, BigDecimal cost, int quantity) {
        CreateProductRequest request = new CreateProductRequest(
                "Product " + sku,
                sku,
                null,
                null,
                null,
                null,
                null,
                null,
                new CreateProductRequest.InitialVendor(companyVendorId, null, cost, quantity, null, null));
        ResponseEntity<ProductResponse> response = restTemplate.exchange(
                "/api/products", HttpMethod.POST, multipartEntity(admin, request), ProductResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private HttpEntity<MultiValueMap<String, Object>> multipartEntity(TenantLoginResponse admin, CreateProductRequest request) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders productPartHeaders = new HttpHeaders();
        productPartHeaders.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(request, productPartHeaders));

        HttpHeaders headers = authHeaders(admin);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return new HttpEntity<>(body, headers);
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

    private record TestPage<T>(List<T> content) {
    }
}
