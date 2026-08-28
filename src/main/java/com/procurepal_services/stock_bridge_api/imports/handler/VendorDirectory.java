package com.procurepal_services.stock_bridge_api.imports.handler;

import com.procurepal_services.stock_bridge_api.entity.CompanyVendor;
import com.procurepal_services.stock_bridge_api.entity.CompanyVendorKind;
import com.procurepal_services.stock_bridge_api.imports.ImportBatchCache;
import com.procurepal_services.stock_bridge_api.imports.NameSimilarity;
import com.procurepal_services.stock_bridge_api.imports.UnresolvedValue;
import com.procurepal_services.stock_bridge_api.imports.ValueMappings;
import com.procurepal_services.stock_bridge_api.repository.CompanyVendorRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductVendorRepository;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Supplier-name matching, shared by both row handlers - "does this tenant already have a
 * {@code Dangote Ltd}, and if not, what are they most likely to have meant?"
 *
 * <p>Both imports carry a {@code vendor_name} column and both must answer it identically. A
 * supplier that resolves on a catalog import and fails to resolve on a stock-in import of the
 * same file would be indefensible, and is exactly what two independently written matchers
 * produce.
 *
 * <h2>Matching by folded name, not by exact string</h2>
 * The name is the only key a spreadsheet has for a supplier - there is no id in the file and
 * there never will be, because the person filling it in does not know one. So the match folds
 * case and collapses whitespace, and everything that survives becomes a question rather than a
 * guess. Designs 6.4 and 7.1 both settle on plain name similarity for the suggestions
 * themselves, matching MULTI_VENDOR_INVENTORY_DESIGN.md sections 7.1/9 rather than inventing a
 * second answer to the same problem.
 */
@Component
@RequiredArgsConstructor
public class VendorDirectory {

    private static final String CACHE_NAMESPACE = "vendors-by-name";

    /**
     * Contact phone on a supplier this import invents.
     *
     * <p>{@code chk_company_vendors_external_shape} requires {@code contact_phone IS NOT NULL} on
     * every EXTERNAL row, and the resolution card collects only a name - by design, because
     * design 13.2's whole argument is that a user forced to leave the import, fill in a supplier
     * form and come back will abandon. So the column is satisfied with an empty string rather
     * than the import either failing or inventing a phone number that looks real. The supplier
     * appears in the directory with a blank contact, which is true, visible, and one click from
     * being filled in.
     */
    private static final String UNKNOWN_CONTACT_PHONE = "";

    private final CompanyVendorRepository companyVendorRepository;
    private final ProductVendorRepository productVendorRepository;

    /** Every active supplier, folded name to entity, memoised for the pass. */
    @SuppressWarnings("unchecked")
    public Map<String, CompanyVendor> byFoldedName(UUID tenantId, ImportBatchCache cache) {
        return cache.get(CACHE_NAMESPACE, tenantId, id -> {
            Map<String, CompanyVendor> byName = new LinkedHashMap<>();
            for (CompanyVendor vendor : companyVendorRepository.findAllByClientIdAndActiveTrueOrderByNameAsc(id)) {
                byName.putIfAbsent(ValueMappings.normalizeKey(vendor.getName()), vendor);
            }
            return byName;
        });
    }

    public CompanyVendor match(UUID tenantId, ImportBatchCache cache, String rawName) {
        if (rawName == null) {
            return null;
        }
        return byFoldedName(tenantId, cache).get(ValueMappings.normalizeKey(rawName));
    }

    /**
     * Closest existing suppliers to an unmatched name, best first, capped at three.
     *
     * <p>Three because the card is a row of buttons and a fourth guess is one nobody reads; and
     * because a long list of weak matches reads as uncertainty, which is worse than offering
     * "add it as new" cleanly. The hint says how many products the supplier already supplies -
     * the one fact that actually distinguishes two similarly-named entries.
     */
    public List<UnresolvedValue.Suggestion> suggestionsFor(UUID tenantId, ImportBatchCache cache, String rawName) {
        return byFoldedName(tenantId, cache).values().stream()
                .map(vendor -> new UnresolvedValue.Suggestion(
                        vendor.getId().toString(),
                        vendor.getName(),
                        productCountHint(tenantId, vendor.getId()),
                        NameSimilarity.score(rawName, vendor.getName())))
                .filter(suggestion -> suggestion.score() >= NameSimilarity.SUGGESTION_FLOOR)
                .sorted(Comparator.comparingDouble(UnresolvedValue.Suggestion::score).reversed())
                .limit(3)
                .toList();
    }

    private String productCountHint(UUID tenantId, UUID companyVendorId) {
        long count = productVendorRepository
                .findAllByClientIdAndCompanyVendorIdAndProductActive(tenantId, companyVendorId)
                .size();
        return count == 0 ? null : count + (count == 1 ? " product" : " products");
    }

    /**
     * Creates a supplier a resolution asked for. Called only from inside a commit transaction -
     * see {@link com.procurepal_services.stock_bridge_api.imports.ValueResolution} for why the
     * creation is deferred to there rather than happening when the user answers the card.
     */
    @Transactional
    public CompanyVendor createInline(String name) {
        return companyVendorRepository.saveAndFlush(CompanyVendor.builder()
                .vendorKind(CompanyVendorKind.EXTERNAL)
                .name(name.trim())
                .contactPhone(UNKNOWN_CONTACT_PHONE)
                .active(true)
                .build());
    }
}
