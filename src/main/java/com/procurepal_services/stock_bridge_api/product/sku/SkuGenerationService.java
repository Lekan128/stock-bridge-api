package com.procurepal_services.stock_bridge_api.product.sku;

import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.entity.ProductSkuSettings;
import com.procurepal_services.stock_bridge_api.entity.SkuResetCadence;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductSkuSettingsRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a tenant's {@link ProductSkuSettings} into concrete SKU strings. Three entry points, each
 * used by a different caller:
 *
 * <ul>
 *   <li>{@link #preview} - the settings screen and the create-product form's disabled, pre-filled
 *       SKU field. Never locks, never writes - a non-committing peek by construction, not a flag
 *       that could be misused.
 *   <li>{@link #generateAndReserveOne} - {@code ProductManagementService.create}, one product.
 *   <li>{@link #generateAndReserveBlock} - {@code ProductCatalogRowHandler.commit}, a whole bulk
 *       import in one round trip instead of one per row.
 * </ul>
 *
 * <p>Both reserving methods lock the settings row ({@code
 * ProductSkuSettingsRepository.findByClientIdForUpdate}) for their entire duration, including any
 * collision retries, so two concurrent callers can never be handed the same rendered SKU - the
 * lock, not the retry loop, is what makes that true.
 */
@Service
@RequiredArgsConstructor
public class SkuGenerationService {

    /** Bounded so a pattern that keeps colliding with legacy manually-created SKUs fails loudly instead of looping. */
    static final int MAX_COLLISION_RETRIES = 5;

    private final ProductSkuSettingsRepository settingsRepository;
    private final ProductRepository productRepository;

    /**
     * A non-committing peek, plus the raw {@code nextSequence} the peek was rendered from.
     *
     * <p>The sequence value is exposed deliberately, not just the rendered string: it is the one
     * piece of this preview that only the server can know (the counter lives in {@code
     * product_sku_settings}, nowhere else). Everything else the pattern can produce - a {@code
     * {NAME:N}} token as the user types a product name, the date tokens - is pure string
     * substitution the client already has everything it needs for (the pattern itself, from
     * {@code GET /api/products/sku-settings}), so re-rendering that live in the UI on every
     * keystroke does not need, and must not cost, a network round trip. This endpoint is called
     * once per form load, not once per keystroke, for exactly that reason.
     *
     * <p>None of this changes who is authoritative for the real SKU: {@code
     * generateAndReserveOne}/{@code generateAndReserveBlock} render and reserve it server-side at
     * save time regardless of anything the client showed or sent - a client-rendered preview is a
     * courtesy, never an input the server trusts.
     */
    public record Preview(String sku, long nextSequence) {}

    @Transactional(readOnly = true)
    public Preview preview(UUID tenantId) {
        ProductSkuSettings settings = requireSettings(settingsRepository.findByClientId(tenantId), tenantId);
        OffsetDateTime now = OffsetDateTime.now();
        String periodKey = computePeriodKey(settings.getResetCadence(), now);
        long sequenceValue =
                Objects.equals(periodKey, settings.getCurrentPeriodKey()) ? settings.getNextSequence() : 1;
        String rendered = SkuPatternRenderer.render(settings.getPattern(), sequenceValue, now, null);
        return new Preview(rendered, sequenceValue);
    }

    /**
     * @param productName feeds a {@code {NAME:N}} token, if the pattern has one; null/blank is
     *     fine, see {@link SkuPatternRenderer}.
     * @throws SkuGenerationExhaustedException every candidate in {@link #MAX_COLLISION_RETRIES}
     *     attempts was already taken.
     */
    @Transactional
    public String generateAndReserveOne(UUID tenantId, String productName) {
        ProductSkuSettings settings =
                requireSettings(settingsRepository.findByClientIdForUpdate(tenantId), tenantId);
        OffsetDateTime now = OffsetDateTime.now();
        applyCadenceRollover(settings, now);

        for (int attempt = 1; attempt <= MAX_COLLISION_RETRIES; attempt++) {
            long sequenceValue = settings.getNextSequence();
            settings.setNextSequence(sequenceValue + 1);
            String rendered = SkuPatternRenderer.render(settings.getPattern(), sequenceValue, now, productName);
            if (productRepository.findByClientIdAndSku(tenantId, rendered).isEmpty()) {
                settingsRepository.save(settings);
                return rendered;
            }
        }
        // Every candidate collided - throwing here rolls back this method's transaction (and, when
        // called from ProductManagementService.create as it normally is, the whole create()
        // transaction with it), so the nextSequence advances made above are undone rather than
        // permanently burning MAX_COLLISION_RETRIES values on a create that never happened.
        throw SkuGenerationExhaustedException.collisionRetries(MAX_COLLISION_RETRIES);
    }

    /**
     * One locked reservation for the whole block, one batch existence check for the common case,
     * and only as many individual existence checks as there are actual collisions - not one round
     * trip per row. Order of the returned list matches {@code productNames}.
     *
     * @param productNames one entry per product to be created, in file/row order; a null/blank
     *     entry is fine wherever the pattern has no {@code {NAME:N}} token to feed.
     * @throws SkuGenerationExhaustedException any single slot exhausted {@link
     *     #MAX_COLLISION_RETRIES} candidates - rolls back the caller's whole commit, matching
     *     {@code ProductCatalogRowHandler}'s existing all-or-nothing commit.
     */
    @Transactional
    public List<String> generateAndReserveBlock(UUID tenantId, List<String> productNames) {
        int count = productNames.size();
        if (count == 0) {
            return List.of();
        }

        ProductSkuSettings settings =
                requireSettings(settingsRepository.findByClientIdForUpdate(tenantId), tenantId);
        OffsetDateTime now = OffsetDateTime.now();
        applyCadenceRollover(settings, now);

        String[] rendered = new String[count];
        for (int i = 0; i < count; i++) {
            long sequenceValue = settings.getNextSequence();
            settings.setNextSequence(sequenceValue + 1);
            rendered[i] = SkuPatternRenderer.render(settings.getPattern(), sequenceValue, now, productNames.get(i));
        }

        // One query covers the whole block for the common (no-collision) case.
        Set<String> taken = new HashSet<>();
        productRepository.findAllByClientIdAndSkuIn(tenantId, List.of(rendered)).stream()
                .map(Product::getSku)
                .forEach(taken::add);

        for (int i = 0; i < count; i++) {
            int attempts = 0;
            while (taken.contains(rendered[i])) {
                attempts++;
                if (attempts > MAX_COLLISION_RETRIES) {
                    throw SkuGenerationExhaustedException.collisionRetries(attempts - 1);
                }
                long sequenceValue = settings.getNextSequence();
                settings.setNextSequence(sequenceValue + 1);
                rendered[i] = SkuPatternRenderer.render(
                        settings.getPattern(), sequenceValue, now, productNames.get(i));
                // A drawn replacement wasn't covered by the initial batch query - check it
                // individually. Reached only on an actual collision, so this doesn't defeat the
                // one-round-trip premise for the ordinary case.
                if (productRepository.findByClientIdAndSku(tenantId, rendered[i]).isPresent()) {
                    taken.add(rendered[i]);
                }
            }
            taken.add(rendered[i]); // reserved within this batch too, so two colliding slots can't both land on the same replacement
        }

        settingsRepository.save(settings);
        return new ArrayList<>(List.of(rendered));
    }

    private static ProductSkuSettings requireSettings(
            Optional<ProductSkuSettings> found, UUID tenantId) {
        return found.orElseThrow(() -> new IllegalStateException(
                "SKU generation requested for tenant " + tenantId + " with no product_sku_settings row - "
                        + "callers must check ProductSkuSettingsService.isEnabled first."));
    }

    private static void applyCadenceRollover(ProductSkuSettings settings, OffsetDateTime now) {
        String periodKey = computePeriodKey(settings.getResetCadence(), now);
        if (!Objects.equals(periodKey, settings.getCurrentPeriodKey())) {
            settings.setNextSequence(1);
            settings.setCurrentPeriodKey(periodKey);
        }
    }

    /** null under NEVER (no period to roll over), '2026' under YEARLY, '2026-09' under MONTHLY. */
    private static String computePeriodKey(SkuResetCadence cadence, OffsetDateTime now) {
        return switch (cadence) {
            case NEVER -> null;
            case YEARLY -> String.format(Locale.ROOT, "%04d", now.getYear());
            case MONTHLY -> String.format(Locale.ROOT, "%04d-%02d", now.getYear(), now.getMonthValue());
        };
    }
}
