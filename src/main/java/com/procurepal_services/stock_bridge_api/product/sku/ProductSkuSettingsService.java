package com.procurepal_services.stock_bridge_api.product.sku;

import com.procurepal_services.stock_bridge_api.entity.ProductSkuSettings;
import com.procurepal_services.stock_bridge_api.entity.SkuResetCadence;
import com.procurepal_services.stock_bridge_api.product.sku.dto.ProductSkuSettingsResponse;
import com.procurepal_services.stock_bridge_api.product.sku.dto.UpdateProductSkuSettingsRequest;
import com.procurepal_services.stock_bridge_api.repository.ProductSkuSettingsRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD over a tenant's {@link ProductSkuSettings} row. Deliberately does not touch {@code
 * nextSequence}/{@code currentPeriodKey} anywhere in this class - see {@link #update} - so
 * reconfiguring the feature can never reset or rewind the counter {@code SkuGenerationService}
 * owns.
 */
@Service
@RequiredArgsConstructor
public class ProductSkuSettingsService {

    private final ProductSkuSettingsRepository repository;

    /** No row for this tenant means auto-generation has never been configured, i.e. off - see {@link ProductSkuSettings}'s javadoc. */
    @Transactional(readOnly = true)
    public boolean isEnabled(UUID tenantId) {
        return repository.findByClientId(tenantId).map(ProductSkuSettings::isEnabled).orElse(false);
    }

    @Transactional(readOnly = true)
    public ProductSkuSettingsResponse get(UUID tenantId) {
        return repository
                .findByClientId(tenantId)
                .map(ProductSkuSettingsResponse::from)
                .orElseGet(ProductSkuSettingsResponse::defaults);
    }

    /**
     * Upsert. {@link SkuPatternValidator} runs unconditionally, even when {@code enabled=false}
     * is being saved - a tenant should not be able to save a broken pattern and have it only
     * surface as an error once they flip the toggle on later.
     */
    @Transactional
    public ProductSkuSettingsResponse update(UUID tenantId, UpdateProductSkuSettingsRequest request) {
        SkuPatternValidator.validate(request.pattern());

        ProductSkuSettings settings = repository.findByClientId(tenantId).orElseGet(() -> ProductSkuSettings.builder()
                .clientId(tenantId)
                .nextSequence(1)
                .resetCadence(SkuResetCadence.NEVER)
                .build());
        settings.setEnabled(request.enabled());
        settings.setPattern(request.pattern());
        settings.setResetCadence(request.resetCadence());
        settings = repository.save(settings);

        return ProductSkuSettingsResponse.from(settings);
    }
}
