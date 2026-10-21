package com.procurepal_services.stock_bridge_api.selling;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.ClientType;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * V44 moves a buying company's unit_price into legacy_unit_price and leaves every seller's alone
 * (SELLING_PRICE_PLAN.md section 4.6).
 *
 * <p>A migration runs once per database, long before this test, so the test runs V44's own UPDATE
 * again - read from the migration file, not copied - against fixtures planted with a price, scoped to
 * exactly those rows so nothing else in the shared database is touched. If someone edits the
 * migration's predicate, this is what notices.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class LegacyUnitPriceMigrationIntegrationTest {

    private static final String MIGRATION = "db/migration/V44__legacy_unit_price.sql";

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void aCompanysPriceMovesAsideAndASellersStaysWhereItIs() throws IOException {
        UUID companyProduct = productWithPrice(tenant("Legacy Price Co", ClientType.COMPANY), "1500.00");
        UUID vendorProduct = productWithPrice(tenant("Legacy Price Vendor", ClientType.VENDOR), "2000.00");
        Client platformOwner = clientRepository.findByPlatformOwnerTrue().orElseThrow();
        // ProcurePal is a COMPANY by type, so this is the row the NOT is_platform_owner clause is for.
        // Never listed, and removed below, so the storefront never sees it.
        UUID ownerProduct = productWithPrice(platformOwner.getId(), "2500.00");
        try {
            runV44UpdateOn(List.of(companyProduct, vendorProduct, ownerProduct));

            Map<String, Object> company = prices(companyProduct);
            assertThat(company.get("unit_price")).isNull();
            assertThat((BigDecimal) company.get("legacy_unit_price")).isEqualByComparingTo("1500.00");

            Map<String, Object> vendor = prices(vendorProduct);
            assertThat((BigDecimal) vendor.get("unit_price")).isEqualByComparingTo("2000.00");
            assertThat(vendor.get("legacy_unit_price")).isNull();

            Map<String, Object> owner = prices(ownerProduct);
            assertThat((BigDecimal) owner.get("unit_price")).isEqualByComparingTo("2500.00");
            assertThat(owner.get("legacy_unit_price")).isNull();
        } finally {
            jdbcTemplate.update("DELETE FROM products WHERE id = ?", ownerProduct);
        }
    }

    @Test
    void runningItAgainChangesNothing() throws IOException {
        UUID companyProduct = productWithPrice(tenant("Legacy Twice Co", ClientType.COMPANY), "900.00");

        runV44UpdateOn(List.of(companyProduct));
        runV44UpdateOn(List.of(companyProduct));

        // The second pass finds unit_price already null and must not overwrite the kept value with it.
        Map<String, Object> company = prices(companyProduct);
        assertThat(company.get("unit_price")).isNull();
        assertThat((BigDecimal) company.get("legacy_unit_price")).isEqualByComparingTo("900.00");
    }

    // ------------------------------------------------------------------------------ fixtures ----

    /** V44's UPDATE statement exactly as the migration file has it, narrowed to {@code productIds}. */
    private void runV44UpdateOn(List<UUID> productIds) throws IOException {
        String update = updateStatement();
        String scope = productIds.stream().map(id -> "'" + id + "'").collect(Collectors.joining(", "));
        jdbcTemplate.update(update.substring(0, update.length() - 1) + "\n  AND p.id IN (" + scope + ");");
    }

    private static String updateStatement() throws IOException {
        String sql;
        try (InputStream in = new ClassPathResource(MIGRATION).getInputStream()) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int start = sql.indexOf("UPDATE products");
        int end = sql.indexOf(';', start);
        assertThat(start).as("V44 has an UPDATE products statement").isNotNegative();
        return sql.substring(start, end + 1);
    }

    private Map<String, Object> prices(UUID productId) {
        return jdbcTemplate.queryForMap("SELECT unit_price, legacy_unit_price FROM products WHERE id = ?", productId);
    }

    private UUID tenant(String name, ClientType type) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        return clientRepository.save(Client.builder()
                        .name(name + " " + unique)
                        .slug((name + "-" + unique).toLowerCase().replace(' ', '-'))
                        .adminContactEmail("owner-" + unique + "@example.com")
                        .clientType(type)
                        .active(true)
                        .build())
                .getId();
    }

    /** Saved straight through the repository: the product service would refuse a company's price. */
    private UUID productWithPrice(UUID tenantId, String unitPrice) {
        TenantContext.set(tenantId);
        try {
            return productRepository.save(Product.builder()
                            .name("Legacy priced " + UUID.randomUUID().toString().substring(0, 6))
                            .sku("LEGACY-" + UUID.randomUUID().toString().substring(0, 8))
                            .unitPrice(new BigDecimal(unitPrice))
                            .quantityOnHand(0)
                            .active(true)
                            .build())
                    .getId();
        } finally {
            TenantContext.clear();
        }
    }
}
