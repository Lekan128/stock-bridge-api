package com.procurepal_services.stock_bridge_api.branch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Only {@link BranchStockService} may move a stock figure (MULTI_BRANCH_BUILD_PLAN.md section 4.1).
 * A write that called {@code Product.setQuantityOnHand} directly would move the company figure and
 * leave every branch's behind - silently, since a one-branch company's mirror repairs itself and
 * would hide it until the day a second branch is added.
 *
 * <p>A scan of the source rather than an architecture library: one rule, no new dependency. The
 * supplier rollup ({@code setQuantityOnHandFromVendor}) is a different figure and is not matched.
 */
class QuantityOnHandWritesTest {

    private static final Path SOURCES = Path.of("src/main/java");
    private static final Path ALLOWED =
            Path.of("src/main/java/com/procurepal_services/stock_bridge_api/branch/BranchStockService.java");

    @Test
    void onlyBranchStockServiceSetsAProductsQuantityOnHand() throws IOException {
        List<String> offenders;
        try (Stream<Path> files = Files.walk(SOURCES)) {
            offenders = files
                    .filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> !file.equals(ALLOWED))
                    .filter(QuantityOnHandWritesTest::setsQuantityOnHand)
                    .map(Path::toString)
                    .toList();
        }

        assertThat(offenders)
                .as("move stock with BranchStockService.apply, never Product.setQuantityOnHand")
                .isEmpty();
    }

    private static boolean setsQuantityOnHand(Path file) {
        try {
            return Files.readString(file).contains("setQuantityOnHand(");
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + file, e);
        }
    }
}
