package com.procurepal_services.stock_bridge_api.selling;

import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.ClientSettingChange;
import com.procurepal_services.stock_bridge_api.entity.ClientSettingChange.ChangedByKind;
import com.procurepal_services.stock_bridge_api.entity.NotificationType;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.entity.ProductPriceChange;
import com.procurepal_services.stock_bridge_api.entity.SettingChangeSource;
import com.procurepal_services.stock_bridge_api.entity.StockUse;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.notification.NotificationService;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.ClientSettingChangeRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.repository.SuperAdminRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import com.procurepal_services.stock_bridge_api.selling.dto.AdoptLegacyPricesRequest;
import com.procurepal_services.stock_bridge_api.selling.dto.LegacyPriceRow;
import com.procurepal_services.stock_bridge_api.selling.dto.SellingSettingsResponse;
import com.procurepal_services.stock_bridge_api.superadmin.ClientNotFoundException;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The company's "sell from your stock" switch (SELLING_PRICE_PLAN.md section 4.2): read, changed by
 * the owner (from settings, the first-week question or an offer in context), or changed by support.
 *
 * <p>Every real change writes one {@link ClientSettingChange} row. Setting the value it already
 * has writes nothing - a double tap or a retried request must not log a change that did not happen,
 * or the "how did they find it" measure counts it twice.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SellingSettingsService {

    /** How the change is attributed to support on the owner's screen - never the account's own name. */
    static final String SUPPORT_NAME = "ProcurePal support";

    private final ClientRepository clientRepository;
    private final ClientSettingChangeRepository changeRepository;
    private final UserRepository userRepository;
    private final SuperAdminRepository superAdminRepository;
    private final NotificationService notificationService;
    private final SellingPolicy sellingPolicy;
    private final ProductRepository productRepository;
    private final SellingPriceService sellingPriceService;

    @Transactional(readOnly = true)
    public SellingSettingsResponse get() {
        return responseFor(currentClient());
    }

    /**
     * The owner (or the first-week card, or an in-context offer) turning selling on or off.
     *
     * @param source where in the app this came from; null means the settings page. SUPPORT is refused.
     */
    @Transactional
    public SellingSettingsResponse update(boolean sellsStock, SettingChangeSource source, UUID actingUserId) {
        return update(sellsStock, source, null, actingUserId);
    }

    /**
     * As above, with the first-week answer when this change IS that answer (V49). The answer is
     * kept whether or not it changes the switch - "We use it" changes nothing else, and still must
     * not be asked again.
     */
    @Transactional
    public SellingSettingsResponse update(boolean sellsStock, SettingChangeSource source, StockUse stockUse, UUID actingUserId) {
        if (source == SettingChangeSource.SUPPORT) {
            throw new InvalidSettingSourceException();
        }
        Client client = currentClient();
        if (stockUse != null) {
            if (!sellingPolicy.canChooseToSellStock(client)) {
                throw new SellingNotAvailableException();
            }
            client.setStockUse(stockUse);
            clientRepository.save(client);
        }
        User actor = userRepository.findById(actingUserId).orElse(null);
        apply(client, sellsStock, source == null ? SettingChangeSource.OWNER : source, actingUserId,
                ChangedByKind.TENANT_USER, actor == null ? "unknown" : actor.getUsername(), null);
        return responseFor(client);
    }

    /**
     * Support turning selling on or off for a company, with a reason. The owner gets an in-app
     * notice saying so and why (plan section 5.5); the screens change under them, and a notice is
     * what keeps that from looking like a fault.
     */
    @Transactional
    public SellingSettingsResponse updateBySupport(UUID clientId, boolean sellsStock, String reason, UUID superAdminId) {
        Client client = clientRepository.findById(clientId).orElseThrow(ClientNotFoundException::new);
        String username = superAdminRepository.findById(superAdminId).map(admin -> admin.getUsername()).orElse("support");
        boolean changed = apply(client, sellsStock, SettingChangeSource.SUPPORT, superAdminId,
                ChangedByKind.SUPER_ADMIN, username, reason.trim());
        if (changed) {
            UUID ownerUserId = userRepository.findFirstByClientIdAndRootTrue(clientId).map(User::getId).orElse(null);
            notificationService.notifyClient(
                    clientId,
                    ownerUserId,
                    NotificationType.ACCOUNT_SETTING_CHANGED,
                    sellsStock ? SUPPORT_NAME + " turned on selling for you" : SUPPORT_NAME + " turned off selling for you",
                    reason.trim(),
                    "/app/company",
                    null);
        }
        return responseFor(client);
    }

    /** @return whether anything changed. */
    private boolean apply(
            Client client,
            boolean sellsStock,
            SettingChangeSource source,
            UUID changedBy,
            ChangedByKind kind,
            String username,
            String reason) {
        if (!sellingPolicy.canChooseToSellStock(client)) {
            throw new SellingNotAvailableException();
        }
        if (client.isSellsStock() == sellsStock) {
            return false;
        }
        boolean before = client.isSellsStock();
        client.setSellsStock(sellsStock);
        clientRepository.save(client);
        changeRepository.save(ClientSettingChange.builder()
                .clientId(client.getId())
                .setting(ClientSettingChange.SELLS_STOCK)
                .oldValue(Boolean.toString(before))
                .newValue(Boolean.toString(sellsStock))
                .source(source)
                .changedBy(changedBy)
                .changedByKind(kind)
                .changedByUsername(username)
                .reason(reason)
                .changedAt(OffsetDateTime.now())
                .build());
        log.info("Selling from stock {} for client={} via {} by {}", sellsStock ? "on" : "off", client.getId(), source, changedBy);
        return true;
    }

    private SellingSettingsResponse responseFor(Client client) {
        ClientSettingChange last = changeRepository
                .findFirstByClientIdAndSettingOrderByChangedAtDesc(client.getId(), ClientSettingChange.SELLS_STOCK)
                .orElse(null);
        return new SellingSettingsResponse(
                client.isSellsStock(),
                sellingPolicy.canChooseToSellStock(client),
                last == null ? null : last.getChangedAt(),
                last == null ? null : changedByName(last),
                last == null ? null : last.getSource(),
                client.getStockUse(),
                productRepository.countByClientIdAndLegacyUnitPriceIsNotNullAndUnitPriceIsNullAndActiveTrue(client.getId()),
                client.getLegacyPricesDismissedAt() != null);
    }

    // ---------------------------------------------------------------- old prices (section 4.6.3)

    /** The products waiting in the old-price review, by name. */
    @Transactional(readOnly = true)
    public Page<LegacyPriceRow> legacyPrices(Pageable pageable) {
        Client client = currentClient();
        Pageable byName = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by("name").and(Sort.by("id")));
        return productRepository
                .findAllByClientIdAndLegacyUnitPriceIsNotNullAndUnitPriceIsNullAndActiveTrue(client.getId(), byName)
                .map(LegacyPriceRow::from);
    }

    /**
     * Adopts old prices as selling prices - each one per stock unit, as V44 kept it, and each logged
     * as a price change (LEGACY_REVIEW). Only for a company that shows selling prices: adopting into a
     * field nobody can see would be a silent change. Products priced since are left alone.
     *
     * @return how many were adopted.
     */
    @Transactional
    public int adoptLegacyPrices(AdoptLegacyPricesRequest request, UUID actingUserId) {
        Client client = currentClient();
        if (!sellingPolicy.showsSellingPrice(client)) {
            throw new SellingNotAvailableException("Turn on selling first, then choose which old prices to use.");
        }
        List<Product> chosen;
        if (Boolean.TRUE.equals(request.all())) {
            chosen = new ArrayList<>();
            // Pages, not one unbounded read: a large catalogue's review must not load it all at once.
            Page<Product> page;
            int number = 0;
            do {
                page = productRepository.findAllByClientIdAndLegacyUnitPriceIsNotNullAndUnitPriceIsNullAndActiveTrue(
                        client.getId(), PageRequest.of(number++, 500, Sort.by("id")));
                chosen.addAll(page.getContent());
            } while (page.hasNext());
        } else {
            List<UUID> ids = request.productIds() == null ? List.of() : request.productIds();
            chosen = ids.stream()
                    .map(id -> productRepository.findByIdAndClientId(id, client.getId()).orElse(null))
                    .filter(product -> product != null && product.getLegacyUnitPrice() != null && product.getUnitPrice() == null)
                    .toList();
        }
        for (Product product : chosen) {
            SellingPriceService.Snapshot before = SellingPriceService.Snapshot.of(product);
            sellingPriceService.apply(product, product.getLegacyUnitPrice(), null);
            sellingPriceService.recordIfChanged(product, before, ProductPriceChange.Source.LEGACY_REVIEW, actingUserId);
        }
        productRepository.saveAll(chosen);
        log.info("Adopted {} old prices as selling prices for client={} by {}", chosen.size(), client.getId(), actingUserId);
        return chosen.size();
    }

    /** "Not now" to the old-price review. The prices stay in legacy_unit_price; only the offer stops. */
    @Transactional
    public SellingSettingsResponse dismissLegacyPrices() {
        Client client = currentClient();
        if (client.getLegacyPricesDismissedAt() == null) {
            client.setLegacyPricesDismissedAt(OffsetDateTime.now());
            clientRepository.save(client);
        }
        return responseFor(client);
    }

    private String changedByName(ClientSettingChange change) {
        if (change.getChangedByKind() == ChangedByKind.SUPER_ADMIN) {
            return SUPPORT_NAME;
        }
        return userRepository.findById(change.getChangedBy()).map(SellingSettingsService::nameOf)
                .orElse(change.getChangedByUsername());
    }

    private static String nameOf(User user) {
        String first = user.getFirstName() == null ? "" : user.getFirstName().trim();
        String last = user.getLastName() == null ? "" : user.getLastName().trim();
        String full = (first + " " + last).trim();
        return full.isEmpty() ? user.getUsername() : full;
    }

    private Client currentClient() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context set");
        }
        return clientRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("Tenant " + tenantId + " has no client row"));
    }
}
