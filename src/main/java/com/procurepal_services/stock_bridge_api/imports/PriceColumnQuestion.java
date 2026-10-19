package com.procurepal_services.stock_bridge_api.imports;

import com.procurepal_services.stock_bridge_api.selling.SellingPolicy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The one question a product sheet can raise about its prices (SELLING_PRICE_PLAN.md sections 5.3
 * and 6.8). Asked at most once per file, and only of a company.
 *
 * <h2>"Is 'Price' what you pay, or what you sell for?"</h2>
 * A company's own spreadsheet very often has one money column called "Price", and for some it is the
 * invoice price and for others the shelf price. Guessing either way is the silent misreading the plan
 * exists to stop: a cost read as a selling price shows the shop selling at cost, and a selling price
 * read as a cost poisons every profit figure after it. So a bare price header ({@link #AMBIGUOUS}) is
 * taken out of the automatic mapping and asked about, and the file cannot be imported until it is
 * answered. Our own headers ("Price you pay for one", "Selling price for one") still map by
 * themselves, so nobody using our template ever sees the question.
 *
 * <h2>"Your sheet has selling prices. Keep them?"</h2>
 * A company that does not record sales has no selling price column, so a sheet's "Selling price"
 * used to be dropped without a word. Now the owner is asked whether to keep the prices - which turns
 * selling on - and anyone else is told the column will be left out, and why.
 */
@Component
@RequiredArgsConstructor
public class PriceColumnQuestion {

    public static final String PRICE_MEANING = "PRICE_MEANING";
    public static final String KEEP_SELLING_PRICES = "KEEP_SELLING_PRICES";

    /** Headers (normalized) that could mean either price. */
    static final Set<String> AMBIGUOUS = Set.of("price", "unit_price", "rate", "unit_rate", "price_per_unit", "unit_cost_price");

    /** Headers (normalized) that can only mean a selling price. */
    static final Set<String> SELLING = Set.of(
            "selling_price", "selling_price_for_one", "sale_price", "sales_price", "list_price", "rrp",
            "retail_price", "selling_price_per_unit", "price_you_sell_for", "what_you_sell_it_for");

    private final SellingPolicy sellingPolicy;

    /** A question raised, by kind and the (normalized) header it is about. */
    public record Raised(String kind, String header) {
    }

    /**
     * Decides whether this file raises the question, taking an ambiguous header out of
     * {@code columnMapping} (and into {@code unmappedHeaders}) when it does.
     *
     * @param headers the file's headers, normalized, in file order.
     * @param columnMapping the automatic mapping - changed in place.
     * @param unmappedHeaders headers that map to nothing - changed in place.
     * @return the question, or null.
     */
    public Raised raise(UUID tenantId, List<String> headers, Map<String, String> columnMapping, List<String> unmappedHeaders) {
        if (!sellingPolicy.asksWhatPriceMeans(tenantId)) {
            return null;
        }
        for (String header : headers) {
            if (header != null && AMBIGUOUS.contains(header)) {
                if (columnMapping.remove(header) != null && !unmappedHeaders.contains(header)) {
                    unmappedHeaders.add(header);
                }
                return new Raised(PRICE_MEANING, header);
            }
        }
        if (!sellingPolicy.showsSellingPrice(tenantId)) {
            for (String header : headers) {
                if (header != null && SELLING.contains(header) && !columnMapping.containsKey(header)) {
                    return new Raised(KEEP_SELLING_PRICES, header);
                }
            }
        }
        return null;
    }
}
