package com.procurepal_services.stock_bridge_api.product.bulk;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.ClientType;
import com.procurepal_services.stock_bridge_api.entity.CompanyVendor;
import com.procurepal_services.stock_bridge_api.entity.CompanyVendorKind;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.entity.ProductVendor;
import com.procurepal_services.stock_bridge_api.entity.ProductVendorPack;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.CompanyVendorRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductVendorPackRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductVendorRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * The stock-in template against a real catalog in the database - the half
 * {@link StockInExcelServiceTest} cannot cover, because "pre-filled from the product's preferred
 * vendor" is a claim about JPA associations and queries, not about spreadsheet writing.
 *
 * <p>Tenant context is set by hand rather than by logging in over HTTP: the thing under test is a
 * service, its tenant scoping is the same {@code TenantContext} the filter would have populated,
 * and going through the whole auth stack to prove a query filters by client_id would be testing
 * the filter, not the query.
 */
@SpringBootTest
@ActiveProfiles("local")
class StockInTemplateServiceIntegrationTest {

    @Autowired
    private StockInTemplateService stockInTemplateService;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CompanyVendorRepository companyVendorRepository;

    @Autowired
    private ProductVendorRepository productVendorRepository;

    @Autowired
    private ProductVendorPackRepository productVendorPackRepository;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /**
     * BULK_IMPORT_CX_PLAN.md task 1.4 against real rows: one row per way each product is bought,
     * supplier from the preferred vendor line, the last price stated per that way of buying, and
     * the two columns a person answers - quantity and price - left empty.
     *
     * <p>{@code lastCostPrice} is ₦900 per KG, as stored (contract section 3.2); the bag row says
     * ₦45,000 and the loose row ₦900.
     */
    @Test
    @Transactional
    void theTemplateIsPreFilledFromTheTenantsRealCatalog() {
        tenant("Stock In Template Co");
        CompanyVendor vendor = vendor("Dangote Nigeria Plc");
        Product rice = product("RICE-50", "Rice 50kg", "KG", "BAG", new BigDecimal("50"), 40, null);
        vendorLine(rice, vendor, new BigDecimal("900"));
        product("OIL-5L", "Groundnut Oil 5L", "LITER", null, null, 10, null);

        Sheet sheet = dataSheetOf(stockInTemplateService.generate(List.of(), StockInTemplateFilter.ALL, null, null));
        List<String> headers = headerNames(sheet);

        List<Row> riceRows = rowsWithSku(sheet, "RICE-50");
        assertThat(riceRows).extracting(row -> row.getCell(headers.indexOf("Comes in")).getStringCellValue())
                .containsExactly("Bag · 50 kg", "Loose · kg");
        assertThat(riceRows).extracting(row -> row.getCell(headers.indexOf("Last price paid (₦)")).getNumericCellValue())
                .containsExactly(45000.0, 900.0);
        assertThat(riceRows).allSatisfy(row -> {
            assertThat(row.getCell(headers.indexOf("Product")).getStringCellValue()).isEqualTo("Rice 50kg");
            assertThat(row.getCell(headers.indexOf("Supplier")).getStringCellValue()).isEqualTo("Dangote Nigeria Plc");
            assertThat(ProductRefs.decode(row.getCell(headers.indexOf("Ref")).getStringCellValue())).contains(rice.getId());
            assertThat(row.getCell(headers.indexOf("How many arrived"))).as("the one column they fill").isNull();
            assertThat(row.getCell(headers.indexOf("Price paid for one (₦)"))).isNull();
        });

        // A product with no supplier line is still listed; only what we genuinely do not know is blank.
        List<Row> oilRows = rowsWithSku(sheet, "OIL-5L");
        assertThat(oilRows).singleElement().satisfies(row -> {
            assertThat(row.getCell(headers.indexOf("Comes in")).getStringCellValue()).isEqualTo("Loose · L");
            assertThat(row.getCell(headers.indexOf("Supplier"))).isNull();
        });
    }

    /** "What do we need to reorder" and "what did we just receive" are usually the same list. */
    @Test
    @Transactional
    void theLowStockFilterNarrowsToProductsAtOrUnderTheirThreshold() {
        tenant("Low Stock Filter Co");
        product("LOW-1", "Nearly out", "KG", null, null, 2, 5);
        product("FINE-1", "Plenty left", "KG", null, null, 900, 5);

        Sheet sheet =
                dataSheetOf(stockInTemplateService.generate(List.of(), StockInTemplateFilter.LOW_STOCK, null, null));

        assertThat(skus(sheet)).contains("LOW-1").doesNotContain("FINE-1");
    }

    /** An explicit selection wins over any filter - it came from the user ticking rows. */
    @Test
    @Transactional
    void anExplicitProductSelectionWinsOverTheFilter() {
        tenant("Selection Wins Co");
        Product chosen = product("PICK-1", "Chosen", "KG", null, null, 5, null);
        product("PICK-2", "Not chosen", "KG", null, null, 5, null);

        Sheet sheet = dataSheetOf(
                stockInTemplateService.generate(List.of(chosen.getId()), StockInTemplateFilter.ALL, null, null));

        assertThat(skus(sheet)).contains("PICK-1").doesNotContain("PICK-2");
    }

    /** One delivery from one supplier is the commonest shape of all. */
    @Test
    @Transactional
    void theByVendorFilterNarrowsToThatSuppliersProducts() {
        tenant("By Vendor Filter Co");
        CompanyVendor dangote = vendor("Dangote Nigeria Plc");
        Product theirs = product("THEIRS-1", "From Dangote", "KG", null, null, 5, null);
        vendorLine(theirs, dangote, new BigDecimal("100"));
        product("OURS-1", "From nobody", "KG", null, null, 5, null);

        Sheet sheet = dataSheetOf(stockInTemplateService.generate(
                List.of(), StockInTemplateFilter.BY_VENDOR, dangote.getId(), null));

        assertThat(skus(sheet)).contains("THEIRS-1").doesNotContain("OURS-1");
    }

    /** Another tenant's product can never appear - a missing row, never an error confirming it exists. */
    @Test
    @Transactional
    void anotherTenantsProductsNeverAppear() {
        tenant("Other Tenant Co");
        Product theirs = product("OTHER-1", "Not yours", "KG", null, null, 5, null);
        tenant("Asking Tenant Co");
        product("MINE-1", "Mine", "KG", null, null, 5, null);

        Sheet sheet = dataSheetOf(
                stockInTemplateService.generate(List.of(theirs.getId()), StockInTemplateFilter.ALL, null, null));

        assertThat(skus(sheet)).doesNotContain("OTHER-1");
    }

    // ------------------------------------------------------------------------------ fixtures ----

    private UUID tenant(String name) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        Client client = clientRepository.save(Client.builder()
                .name(name + " " + unique)
                .slug((name + "-" + unique).toLowerCase().replace(' ', '-'))
                .adminContactEmail("owner-" + unique + "@example.com")
                .clientType(ClientType.COMPANY)
                .active(true)
                .build());
        TenantContext.set(client.getId());
        return client.getId();
    }

    private CompanyVendor vendor(String name) {
        // clientId is never set by hand - TenantAwareEntity's @PrePersist stamps it from
        // TenantContext, which tenant() above has already pointed at this tenant.
        CompanyVendor vendor = new CompanyVendor();
        vendor.setVendorKind(CompanyVendorKind.EXTERNAL);
        vendor.setName(name);
        vendor.setContactPhone("08030000000");
        vendor.setActive(true);
        return companyVendorRepository.save(vendor);
    }

    private Product product(
            String sku,
            String name,
            String unitOfMeasure,
            String packagingUnit,
            BigDecimal packagingSize,
            int quantityOnHand,
            Integer lowStockThreshold) {
        Product product = Product.builder()
                .name(name)
                .sku(sku)
                .unitOfMeasure(unitOfMeasure)
                .packagingUnit(packagingUnit)
                .packagingSize(packagingSize)
                .quantityOnHand(quantityOnHand)
                .lowStockThreshold(lowStockThreshold)
                .active(true)
                .build();
        return productRepository.saveAndFlush(product);
    }

    /** @param lastCost per STOCK UNIT, which is what {@code ProductVendorPack.lastCostPrice} is - contract 3.2.
     *  A bare (no packaging) default pack - the product's own BAG/50 pack, set separately via
     *  {@link #product}, is what actually contributes "Bag of 50 kg" to the unit set; this pack
     *  exists only to carry the vendor's cost. */
    private void vendorLine(Product product, CompanyVendor vendor, BigDecimal lastCost) {
        ProductVendor line = new ProductVendor();
        line.setProduct(product);
        line.setCompanyVendor(vendor);
        line.setPreferred(true);
        line = productVendorRepository.saveAndFlush(line);

        ProductVendorPack pack = new ProductVendorPack();
        pack.setProductVendor(line);
        pack.setLastCostPrice(lastCost);
        pack.setDefault(true);
        productVendorPackRepository.saveAndFlush(pack);
    }

    /**
     * The generated sheet, read back. The workbook is closed before the sheet is used, which is
     * safe for XSSF specifically - it is a fully in-memory model, unlike the streaming reader on
     * the parse side - and stated here because it is the kind of assumption that is invisible
     * until it is wrong.
     */
    private Sheet dataSheetOf(byte[] file) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            return workbook.getSheet("Delivery");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<String> headerNames(Sheet sheet) {
        List<String> names = new ArrayList<>();
        for (Cell cell : sheet.getRow(0)) {
            names.add(cell.getStringCellValue());
        }
        return names;
    }

    private List<String> skus(Sheet sheet) {
        int code = headerNames(sheet).indexOf("Your code");
        List<String> skus = new ArrayList<>();
        for (int i = 2; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row != null && row.getCell(code) != null) {
                skus.add(row.getCell(code).getStringCellValue());
            }
        }
        return skus;
    }

    private List<Row> rowsWithSku(Sheet sheet, String sku) {
        int code = headerNames(sheet).indexOf("Your code");
        List<Row> rows = new ArrayList<>();
        for (int i = 2; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row != null && row.getCell(code) != null && sku.equals(row.getCell(code).getStringCellValue())) {
                rows.add(row);
            }
        }
        assertThat(rows).as("rows for sku %s", sku).isNotEmpty();
        return rows;
    }
}
