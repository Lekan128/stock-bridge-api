package com.procurepal_services.stock_bridge_api.product.bulk;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.ClientType;
import com.procurepal_services.stock_bridge_api.entity.CompanyVendor;
import com.procurepal_services.stock_bridge_api.entity.CompanyVendorKind;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.entity.ProductVendor;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.CompanyVendorRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
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

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /**
     * The whole of UNIT_UX_CONTRACT.md section 5.2's column table, asserted against real rows:
     * supplier from the preferred vendor line, every way of counting the product in
     * {@code how_you_count_it}, the likeliest of them pre-filled into {@code counted_in}, the cost
     * stated in that option's terms, date today - and quantity, the one column the user fills,
     * empty.
     *
     * <p>{@code lastCostPrice} is ₦900, per KG, because contract section 3.2 makes every stored
     * price per stock unit. The sheet has to state it as ₦45,000 against the 50 kg bag it pre-fills,
     * or a user who accepts the pre-fill imports a fiftieth of what they paid - which is exactly the
     * round trip that made UNIT_UX_REMEDIATION_PLAN.md P0-1 self-perpetuating.
     */
    @Test
    @Transactional
    void theTemplateIsPreFilledFromTheTenantsRealCatalog() {
        tenant("Stock In Template Co");
        CompanyVendor vendor = vendor("Dangote Nigeria Plc");
        Product rice = product("RICE-50", "Rice 50kg", "KG", "BAG", new BigDecimal("50"), 40, null);
        vendorLine(rice, vendor, new BigDecimal("900"), new BigDecimal("50"));
        product("OIL-5L", "Groundnut Oil 5L", "LITER", null, null, 10, null);

        Sheet sheet = firstSheetOf(stockInTemplateService.generate(List.of(), StockInTemplateFilter.ALL, null, null));
        List<String> headers = headerNames(sheet);

        Row riceRow = rowWithSku(sheet, "RICE-50");
        assertThat(riceRow.getCell(headers.indexOf("product_name")).getStringCellValue()).isEqualTo("Rice 50kg");
        assertThat(riceRow.getCell(headers.indexOf("vendor_name")).getStringCellValue())
                .isEqualTo("Dangote Nigeria Plc");
        assertThat(riceRow.getCell(headers.indexOf("how_you_count_it")).getStringCellValue())
                .as("both valid answers, on the row, beside the cell that asks the question")
                .isEqualTo("kg · or Bag of 50 kg");
        assertThat(riceRow.getCell(headers.indexOf("counted_in")).getStringCellValue()).isEqualTo("Bag of 50 kg");
        assertThat(riceRow.getCell(headers.indexOf("cost_per_unit")).getNumericCellValue())
                .as("₦900 per kg, stated per the 50 kg bag this row is counted in")
                .isEqualTo(45000.0);
        assertThat(riceRow.getCell(headers.indexOf("received_date"))).isNotNull();
        assertThat(riceRow.getCell(headers.indexOf("quantity"))).as("the one column they fill").isNull();

        // A product with no supplier line still pre-fills its unit set - only what we genuinely do
        // not know is left blank.
        Row oilRow = rowWithSku(sheet, "OIL-5L");
        assertThat(oilRow.getCell(headers.indexOf("how_you_count_it")).getStringCellValue()).isEqualTo("L");
        assertThat(oilRow.getCell(headers.indexOf("counted_in")).getStringCellValue()).isEqualTo("L");
        assertThat(oilRow.getCell(headers.indexOf("vendor_name"))).isNull();
    }

    /** "What do we need to reorder" and "what did we just receive" are usually the same list. */
    @Test
    @Transactional
    void theLowStockFilterNarrowsToProductsAtOrUnderTheirThreshold() {
        tenant("Low Stock Filter Co");
        product("LOW-1", "Nearly out", "KG", null, null, 2, 5);
        product("FINE-1", "Plenty left", "KG", null, null, 900, 5);

        Sheet sheet =
                firstSheetOf(stockInTemplateService.generate(List.of(), StockInTemplateFilter.LOW_STOCK, null, null));

        assertThat(skus(sheet)).contains("LOW-1").doesNotContain("FINE-1");
    }

    /** An explicit selection wins over any filter - it came from the user ticking rows. */
    @Test
    @Transactional
    void anExplicitProductSelectionWinsOverTheFilter() {
        tenant("Selection Wins Co");
        Product chosen = product("PICK-1", "Chosen", "KG", null, null, 5, null);
        product("PICK-2", "Not chosen", "KG", null, null, 5, null);

        Sheet sheet = firstSheetOf(
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
        vendorLine(theirs, dangote, new BigDecimal("100"), null);
        product("OURS-1", "From nobody", "KG", null, null, 5, null);

        Sheet sheet = firstSheetOf(stockInTemplateService.generate(
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

        Sheet sheet = firstSheetOf(
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

    /** @param lastCost per STOCK UNIT, which is what {@code ProductVendor.lastCostPrice} is - contract 3.2. */
    private void vendorLine(Product product, CompanyVendor vendor, BigDecimal lastCost, BigDecimal packagingSize) {
        ProductVendor line = new ProductVendor();
        line.setProduct(product);
        line.setCompanyVendor(vendor);
        line.setLastCostPrice(lastCost);
        line.setDefaultPackagingSize(packagingSize);
        line.setPreferred(true);
        productVendorRepository.saveAndFlush(line);
    }

    /**
     * The generated sheet, read back. The workbook is closed before the sheet is used, which is
     * safe for XSSF specifically - it is a fully in-memory model, unlike the streaming reader on
     * the parse side - and stated here because it is the kind of assumption that is invisible
     * until it is wrong.
     */
    private Sheet firstSheetOf(byte[] file) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            return workbook.getSheetAt(0);
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
        List<String> skus = new ArrayList<>();
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row != null && row.getCell(0) != null) {
                skus.add(row.getCell(0).getStringCellValue());
            }
        }
        return skus;
    }

    private Row rowWithSku(Sheet sheet, String sku) {
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row != null && row.getCell(0) != null && sku.equals(row.getCell(0).getStringCellValue())) {
                return row;
            }
        }
        throw new AssertionError("No row for sku " + sku);
    }
}
