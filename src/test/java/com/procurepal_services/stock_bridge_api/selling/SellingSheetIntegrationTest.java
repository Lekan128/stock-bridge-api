package com.procurepal_services.stock_bridge_api.selling;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.imports.dto.ImportResultResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.ImportRowResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.ImportSessionResponse;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
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
 * Selling prices on the product sheet (SELLING_PRICE_PLAN.md section 6.8, Phase 5): a company's
 * price is read per pack like its cost and kept as typed, round-trips through export, edit, upload
 * and undo; below cost is a warning; a bare "Price" column is asked about; a sheet's selling prices
 * are offered to a company that does not sell yet; and "Add prices in a sheet" prices what has none.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class SellingSheetIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final String SELLING_HEADER = "Selling price for one (₦)";

    private final DataFormatter formatter = new DataFormatter();

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void aCompanysSellingPriceIsPerPackKeptAsTypedAndRoundTripsThroughExportEditUploadAndUndo() {
        TenantLoginResponse shop = signup("Sheet Price Co");
        turnOnSelling(shop);

        // The template says what the column means.
        try (XSSFWorkbook template = open(download(shop, "/api/imports/templates/products"))) {
            List<String> headers = headersOf(template.getSheet("Products"));
            assertThat(headers).contains(SELLING_HEADER);
            assertThat(headers.indexOf(SELLING_HEADER)).isEqualTo(headers.indexOf("Price you pay for one (₦)") + 1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        String sku = "MILK-1";
        ImportSessionResponse created = uploadCsv(shop,
                "name,sku,comes in,size of one,price you pay for one,selling price for one\n"
                        + "Milk tin," + sku + ",Carton,24 piece,4800,5000\n");
        assertThat(created.priceQuestion()).isNull();
        commit(shop, created.id());

        ProductResponse milk = productBySku(shop, sku);
        assertThat(milk.unitPrice()).isEqualByComparingTo("208.33");
        assertThat(milk.unitPriceEntered()).isEqualByComparingTo("5000");
        assertThat(milk.unitPriceEnteredUnit()).isEqualTo("CARTON");

        // Export: 5,000 a carton, exactly as typed - not 208.33 x 24.
        byte[] export = download(shop, "/api/products/export");
        assertThat(sellingCell(export, "Products", sku)).isEqualTo("5,000.00");

        // Uploaded back unchanged: nothing about the price moves.
        commit(shop, uploadXlsx(shop, export).id());
        assertThat(productBySku(shop, sku).unitPriceEntered()).isEqualByComparingTo("5000");

        // Edited to 5,400 and uploaded: the new price, per carton.
        UUID edited = uploadXlsx(shop, setSelling(export, "Products", sku, 5400)).id();
        commit(shop, edited);
        ProductResponse raised = productBySku(shop, sku);
        assertThat(raised.unitPriceEntered()).isEqualByComparingTo("5400");
        assertThat(raised.unitPrice()).isEqualByComparingTo("225.00");

        // Undo puts the old price back, typed figure and all.
        undo(shop, edited);
        ProductResponse back = productBySku(shop, sku);
        assertThat(back.unitPriceEntered()).isEqualByComparingTo("5000");
        assertThat(back.unitPriceEnteredUnit()).isEqualTo("CARTON");
        assertThat(back.unitPrice()).isEqualByComparingTo("208.33");
    }

    @Test
    void sellingBelowCostIsAWarningWithBothFiguresNotAnError() {
        TenantLoginResponse shop = signup("Below Cost Co");
        turnOnSelling(shop);

        ImportSessionResponse session = uploadCsv(shop,
                "name,sku,comes in,size of one,price you pay for one,selling price for one\n"
                        + "Oil keg,OIL-1,Keg,25 l,52000,48000\n");
        assertThat(session.errorCount()).isZero();
        assertThat(session.warningCount()).isEqualTo(1);
        ImportRowResponse row = rows(shop, session.id(), "WARNING").get(0);
        assertThat(row.warnings().toString())
                .contains("column=unit_price")
                .contains("₦48,000 a keg")
                .contains("₦52,000 a keg");
        commit(shop, session.id());
    }

    @Test
    void aBarePriceColumnIsAskedAboutAndHoldsTheFileUntilAnswered() {
        TenantLoginResponse shop = signup("Bare Price Co");

        ImportSessionResponse session = uploadCsv(shop, "name,sku,price\nBeans,BEANS-1,1200\nGarri,GARRI-1,800\n");
        assertThat(session.priceQuestion()).isNotNull();
        assertThat(session.priceQuestion().kind()).isEqualTo("PRICE_MEANING");
        assertThat(session.priceQuestion().header()).isEqualTo("price");
        assertThat(session.priceQuestion().samples()).containsExactly("1200", "800");
        assertThat(session.priceQuestion().options()).containsExactly("COST", "SELLING", "IGNORE");
        assertThat(session.priceQuestion().blocking()).isTrue();
        assertThat(session.columnMapping()).doesNotContainKey("price");
        assertThat(restTemplate.exchange("/api/imports/" + session.id() + "/commit", HttpMethod.POST,
                new HttpEntity<>(auth(shop)), String.class).getStatusCode().is2xxSuccessful())
                .as("an unanswered price question holds the file")
                .isFalse();

        ImportSessionResponse answered = answer(shop, session.id(), "COST");
        assertThat(answered.priceQuestion()).isNull();
        assertThat(answered.columnMapping()).containsEntry("price", "cost_price");
        commit(shop, answered.id());
        assertThat(productBySku(shop, "BEANS-1").costPrice()).isEqualByComparingTo("1200");
        assertThat(productBySku(shop, "BEANS-1").unitPrice()).isNull();
    }

    @Test
    void answeringSellingTurnsSellingOnForTheOwnerAndKeepsThePrices() {
        TenantLoginResponse shop = signup("Price Is Selling Co");

        ImportSessionResponse session = uploadCsv(shop, "name,sku,unit price\nBread,BREAD-1,1500\n");
        assertThat(session.priceQuestion().kind()).isEqualTo("PRICE_MEANING");
        ImportSessionResponse answered = answer(shop, session.id(), "SELLING");
        assertThat(answered.columnMapping()).containsEntry("unit_price", "unit_price");
        commit(shop, answered.id());

        assertThat(sellingOn(shop)).isTrue();
        assertThat(productBySku(shop, "BREAD-1").unitPrice()).isEqualByComparingTo("1500");
    }

    @Test
    void aSheetsSellingPricesAreOfferedToACompanyThatDoesNotSellAndIgnoringThemChangesNothing() {
        TenantLoginResponse shop = signup("Keep Prices Co");

        ImportSessionResponse session = uploadCsv(shop, "name,sku,selling price\nSoap,SOAP-1,350\n");
        assertThat(session.priceQuestion().kind()).isEqualTo("KEEP_SELLING_PRICES");
        assertThat(session.priceQuestion().options()).containsExactly("SELLING", "IGNORE");
        assertThat(session.priceQuestion().canTurnOnSelling()).isTrue();

        ImportSessionResponse answered = answer(shop, session.id(), "IGNORE");
        assertThat(answered.priceQuestion()).isNull();
        commit(shop, answered.id());
        assertThat(sellingOn(shop)).isFalse();
        assertThat(productBySku(shop, "SOAP-1").unitPrice()).isNull();
    }

    @Test
    void ourOwnHeadersNeverAskAndAMarketplaceStyleSheetForASellingCompanyMapsByItself() {
        TenantLoginResponse shop = signup("No Question Co");
        turnOnSelling(shop);
        ImportSessionResponse session = uploadCsv(shop,
                "product name,your code,price you pay for one,selling price for one\nSalt,SALT-1,100,150\n");
        assertThat(session.priceQuestion()).isNull();
        assertThat(session.columnMapping()).containsEntry("selling_price_for_one", "unit_price");
    }

    @Test
    void addPricesInASheetListsWhatHasNoPriceAndPricesItThroughTheOrdinaryUpload() {
        TenantLoginResponse shop = signup("Price Sheet Co");
        turnOnSelling(shop);
        commit(shop, uploadCsv(shop,
                "name,sku,comes in,size of one,price you pay for one,selling price for one\n"
                        + "Rice,RICE-1,Bag,50 kg,42000,46000\n"
                        + "Sugar,SUGAR-1,Bag,50 kg,38000,\n"
                        + "Pens,PENS-1,,piece,150,\n").id());

        byte[] sheet = download(shop, "/api/products/export/prices");
        List<String> names = new ArrayList<>();
        List<String> headers;
        try (XSSFWorkbook workbook = open(sheet)) {
            Sheet prices = workbook.getSheet("Prices");
            headers = headersOf(prices);
            for (int i = 2; i <= prices.getLastRowNum(); i++) {
                names.add(formatter.formatCellValue(prices.getRow(i).getCell(0)));
            }
            assertThat(formatter.formatCellValue(prices.getRow(3).getCell(headers.indexOf("You pay for one (₦, for reference)"))))
                    .isEqualTo("38,000.00");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(headers).containsExactly("Product name *", "Your code *", "Comes in", "Size of one",
                "You pay for one (₦, for reference)", SELLING_HEADER, "Ref");
        assertThat(names).containsExactly("Pens", "Sugar");

        ImportSessionResponse upload = uploadXlsx(shop, setSelling(sheet, "Prices", "SUGAR-1", 41000));
        assertThat(upload.priceQuestion()).isNull();
        assertThat(upload.errorCount()).isZero();
        commit(shop, upload.id());

        ProductResponse sugar = productBySku(shop, "SUGAR-1");
        assertThat(sugar.unitPriceEntered()).isEqualByComparingTo("41000");
        assertThat(sugar.unitPriceEnteredUnit()).isEqualTo("BAG");
        assertThat(sugar.costPrice()).isEqualByComparingTo("760.00");
        assertThat(productBySku(shop, "PENS-1").unitPrice()).isNull();
        assertThat(productBySku(shop, "RICE-1").unitPriceEntered()).isEqualByComparingTo("46000");

        TenantLoginResponse notSelling = signup("No Price Sheet Co");
        assertThat(restTemplate.exchange("/api/products/export/prices", HttpMethod.GET,
                new HttpEntity<>(auth(notSelling)), byte[].class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void aBulkStockInListsProductsThatNowCostAtLeastWhatTheySellFor() {
        TenantLoginResponse shop = signup("Check Prices Co");
        turnOnSelling(shop);
        commit(shop, uploadCsv(shop,
                "name,sku,comes in,size of one,price you pay for one,selling price for one\n"
                        + "Milk tin,MILK-2,Carton,24 piece,4800,5000\n"
                        + "Tea,TEA-2,Carton,24 piece,3000,4000\n").id());

        ImportSessionResponse delivery = upload(shop,
                ("product,your code,comes in,how many arrived,price paid for one\n"
                        + "Milk tin,MILK-2,Carton,2,5200\n"
                        + "Tea,TEA-2,Carton,2,3100\n").getBytes(StandardCharsets.UTF_8),
                "delivery.csv", null, "STOCK_IN");
        assertThat(delivery.errorCount()).isZero();
        commit(shop, delivery.id());
        // Read back as the result page reads it, after the commit.
        ResponseEntity<ImportResultResponse> result = restTemplate.exchange(
                "/api/imports/" + delivery.id() + "/result", HttpMethod.GET, new HttpEntity<>(auth(shop)),
                ImportResultResponse.class);
        assertThat(result.getBody().pricesToCheck()).singleElement().satisfies(check -> {
            assertThat(check.productName()).isEqualTo("Milk tin");
            assertThat(check.costEach()).isEqualByComparingTo("5200");
            assertThat(check.sellingEach()).isEqualByComparingTo("5000");
            assertThat(check.per()).isEqualTo("a carton");
        });
        // The stock-in never touches the selling price.
        assertThat(productBySku(shop, "MILK-2").unitPriceEntered()).isEqualByComparingTo("5000");
    }

    // ------------------------------------------------------------------------------ sheets

    private XSSFWorkbook open(byte[] xlsx) {
        try {
            return new XSSFWorkbook(new ByteArrayInputStream(xlsx));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<String> headersOf(Sheet sheet) {
        List<String> headers = new ArrayList<>();
        for (Cell cell : sheet.getRow(0)) {
            headers.add(cell.getStringCellValue());
        }
        return headers;
    }

    private Row rowFor(Sheet sheet, List<String> headers, String sku) {
        for (int i = 2; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row != null && sku.equals(formatter.formatCellValue(row.getCell(headers.indexOf("Your code *"))))) {
                return row;
            }
        }
        throw new AssertionError("No row for " + sku);
    }

    private String sellingCell(byte[] xlsx, String sheetName, String sku) {
        try (XSSFWorkbook workbook = open(xlsx)) {
            Sheet sheet = workbook.getSheet(sheetName);
            List<String> headers = headersOf(sheet);
            return formatter.formatCellValue(rowFor(sheet, headers, sku).getCell(headers.indexOf(SELLING_HEADER)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private byte[] setSelling(byte[] xlsx, String sheetName, String sku, double price) {
        try (XSSFWorkbook workbook = open(xlsx)) {
            Sheet sheet = workbook.getSheet(sheetName);
            List<String> headers = headersOf(sheet);
            Row row = rowFor(sheet, headers, sku);
            Cell cell = row.getCell(headers.indexOf(SELLING_HEADER));
            (cell == null ? row.createCell(headers.indexOf(SELLING_HEADER)) : cell).setCellValue(price);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --------------------------------------------------------------------------- transport

    private byte[] download(TenantLoginResponse tenant, String path) {
        ResponseEntity<byte[]> response =
                restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(auth(tenant)), byte[].class);
        assertThat(response.getStatusCode()).as("GET %s", path).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ImportSessionResponse uploadCsv(TenantLoginResponse tenant, String csv) {
        return upload(tenant, csv.getBytes(StandardCharsets.UTF_8), "prices.csv", null, "PRODUCT_CATALOG");
    }

    private ImportSessionResponse uploadXlsx(TenantLoginResponse tenant, byte[] xlsx) {
        return upload(tenant, xlsx, "prices.xlsx",
                MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"), "PRODUCT_CATALOG");
    }

    private ImportSessionResponse upload(TenantLoginResponse tenant, byte[] file, String filename, MediaType type, String kind) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders part = new HttpHeaders();
        if (type != null) {
            part.setContentType(type);
        }
        body.add("file", new HttpEntity<>(new ByteArrayResource(file) {
            @Override
            public String getFilename() {
                return filename;
            }
        }, part));
        body.add("kind", kind);
        if (kind.equals("PRODUCT_CATALOG")) {
            body.add("mode", "CREATE_ONLY");
        }
        HttpHeaders headers = auth(tenant);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<ImportSessionResponse> response = restTemplate.exchange(
                "/api/imports", HttpMethod.POST, new HttpEntity<>(body, headers), ImportSessionResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private ImportSessionResponse answer(TenantLoginResponse tenant, UUID sessionId, String answer) {
        HttpHeaders headers = auth(tenant);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<ImportSessionResponse> response = restTemplate.exchange(
                "/api/imports/" + sessionId + "/price-question", HttpMethod.POST,
                new HttpEntity<>(Map.of("answer", answer), headers), ImportSessionResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private void commit(TenantLoginResponse tenant, UUID sessionId) {
        ResponseEntity<ImportResultResponse> response = restTemplate.exchange(
                "/api/imports/" + sessionId + "/commit", HttpMethod.POST, new HttpEntity<>(auth(tenant)),
                ImportResultResponse.class);
        assertThat(response.getStatusCode()).as("commit").isEqualTo(HttpStatus.OK);
    }

    private void undo(TenantLoginResponse tenant, UUID sessionId) {
        ResponseEntity<ImportResultResponse> response = restTemplate.exchange(
                "/api/imports/" + sessionId + "/undo", HttpMethod.POST, new HttpEntity<>(auth(tenant)),
                ImportResultResponse.class);
        assertThat(response.getStatusCode()).as("undo").isEqualTo(HttpStatus.OK);
    }

    private List<ImportRowResponse> rows(TenantLoginResponse tenant, UUID sessionId, String status) {
        return restTemplate.exchange("/api/imports/" + sessionId + "/rows?status=" + status, HttpMethod.GET,
                        new HttpEntity<>(auth(tenant)), new ParameterizedTypeReference<TestPage<ImportRowResponse>>() {})
                .getBody()
                .content();
    }

    private ProductResponse productBySku(TenantLoginResponse tenant, String sku) {
        return restTemplate.exchange("/api/products?search=" + sku + "&size=50", HttpMethod.GET,
                        new HttpEntity<>(auth(tenant)), new ParameterizedTypeReference<TestPage<ProductResponse>>() {})
                .getBody()
                .content()
                .stream()
                .filter(product -> sku.equals(product.sku()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No product " + sku));
    }

    private void turnOnSelling(TenantLoginResponse tenant) {
        HttpHeaders headers = auth(tenant);
        headers.setContentType(MediaType.APPLICATION_JSON);
        assertThat(restTemplate.exchange("/api/company/selling", HttpMethod.PUT,
                new HttpEntity<>(Map.of("sellsStock", true), headers), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private boolean sellingOn(TenantLoginResponse tenant) {
        Map<?, ?> settings = restTemplate.exchange("/api/company/selling", HttpMethod.GET, new HttpEntity<>(auth(tenant)), Map.class)
                .getBody();
        return Boolean.TRUE.equals(settings.get("sellsStock"));
    }

    private TenantLoginResponse signup(String name) {
        String unique = UUID.randomUUID().toString();
        ClientSignupRequest request = new ClientSignupRequest(
                name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD);
        return restTemplate.postForObject("/api/clients/signup", request, TenantLoginResponse.class);
    }

    private HttpHeaders auth(TenantLoginResponse tenant) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tenant.tokens().accessToken());
        return headers;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TestPage<T>(List<T> content, int totalElements) {
    }
}
