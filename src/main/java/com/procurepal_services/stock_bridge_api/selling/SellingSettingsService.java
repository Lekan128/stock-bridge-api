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

    /** Who may set a selling price (V52). */
    private final SellingPricePermission sellingPricePermission;
    private final ClientRepository clientRepository;
    private final ClientSettingChangeRepository changeRepository;
    private final UserRepository userRepository;
    private final SuperAdminRepository superAdminRepository;
    private final NotificationService notificationService;
    private final SellingPolicy sellingPolicy;
    private final ProductRepository productRepository;
    private final SellingPriceService sellingPriceService;
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;

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

    /**
     * SuperAdmin's "Reset demo" turning selling on for the demo company, both ways of using stock. Goes
     * through support's switch, not the owner's: in the pilot a brand-new company may not choose selling
     * itself, and the demo is always brand new. No notice to the owner - the reset has just made
     * everything they see. Logged like any support change.
     */
    @Transactional
    public void turnOnForDemo(UUID clientId) {
        Client client = clientRepository.findById(clientId).orElseThrow(ClientNotFoundException::new);
        apply(client, true, SettingChangeSource.SUPPORT, null, ChangedByKind.SUPER_ADMIN, "demo reset",
                "Demo company reset");
        client.setStockUse(StockUse.BOTH);
        clientRepository.save(client);
        // The demo is a restaurant: it sells what it makes too (V90).
        applyMadeHere(client, true, SettingChangeSource.SUPPORT, null, ChangedByKind.SUPER_ADMIN, "demo reset",
                "Demo company reset");
    }

    /** The owner turning "Sell things you make" on or off (V90). Needs selling on to turn it on. */
    @Transactional
    public SellingSettingsResponse updateMadeHere(boolean on, UUID actingUserId) {
        Client client = currentClient();
        User actor = userRepository.findById(actingUserId).orElse(null);
        applyMadeHere(client, on, SettingChangeSource.OWNER, actingUserId, ChangedByKind.TENANT_USER,
                actor == null ? "unknown" : actor.getUsername(), null);
        return responseFor(client);
    }

    /** The owner turning "Count cash at open and close" on or off (V97, Step 13). Needs selling on to turn it on. */
    @Transactional
    public SellingSettingsResponse updateCountsCash(boolean on, UUID actingUserId) {
        Client client = currentClient();
        if (on && !client.isSellsStock()) {
            throw new SellingNotAvailableException("Turn on selling first: it counts the cash from sales.");
        }
        if (client.isCountsCash() != on) {
            User actor = userRepository.findById(actingUserId).orElse(null);
            client.setCountsCash(on);
            clientRepository.save(client);
            changeRepository.save(ClientSettingChange.builder()
                    .clientId(client.getId())
                    .setting(ClientSettingChange.COUNTS_CASH)
                    .oldValue(Boolean.toString(!on))
                    .newValue(Boolean.toString(on))
                    .source(SettingChangeSource.OWNER)
                    .changedBy(actingUserId)
                    .changedByKind(ChangedByKind.TENANT_USER)
                    .changedByUsername(actor == null ? "unknown" : actor.getUsername())
                    .changedAt(OffsetDateTime.now())
                    .build());
        }
        return responseFor(client);
    }

    /** The owner choosing how long after a close a drawer can be opened again (V98): 1 to 24 hours. */
    @Transactional
    public SellingSettingsResponse updateCashReopenHours(int hours, UUID actingUserId) {
        if (hours < 1 || hours > 24) {
            throw new IllegalArgumentException("Choose between 1 and 24 hours.");
        }
        Client client = currentClient();
        if (client.getCashReopenHours() != hours) {
            User actor = userRepository.findById(actingUserId).orElse(null);
            int old = client.getCashReopenHours();
            client.setCashReopenHours(hours);
            clientRepository.save(client);
            changeRepository.save(ClientSettingChange.builder()
                    .clientId(client.getId())
                    .setting(ClientSettingChange.CASH_REOPEN_HOURS)
                    .oldValue(Integer.toString(old))
                    .newValue(Integer.toString(hours))
                    .source(SettingChangeSource.OWNER)
                    .changedBy(actingUserId)
                    .changedByKind(ChangedByKind.TENANT_USER)
                    .changedByUsername(actor == null ? "unknown" : actor.getUsername())
                    .changedAt(OffsetDateTime.now())
                    .build());
        }
        return responseFor(client);
    }

    /** Support turning it on or off for a company, with a reason; the owner is told, as for selling itself. */
    @Transactional
    public SellingSettingsResponse updateMadeHereBySupport(UUID clientId, boolean on, String reason, UUID superAdminId) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Say why you are changing this - the owner is told.");
        }
        Client client = clientRepository.findById(clientId).orElseThrow(ClientNotFoundException::new);
        String username = superAdminRepository.findById(superAdminId).map(admin -> admin.getUsername()).orElse("support");
        boolean changed = applyMadeHere(client, on, SettingChangeSource.SUPPORT, superAdminId, ChangedByKind.SUPER_ADMIN,
                username, reason.trim());
        if (changed) {
            UUID ownerUserId = userRepository.findFirstByClientIdAndRootTrue(clientId).map(User::getId).orElse(null);
            notificationService.notifyClient(clientId, ownerUserId, NotificationType.ACCOUNT_SETTING_CHANGED,
                    on ? SUPPORT_NAME + " turned on selling things you make" : SUPPORT_NAME + " turned off selling things you make",
                    reason.trim(), "/app/company", null);
        }
        return responseFor(client);
    }

    /** @return whether anything changed. Turning it on needs selling on; turning it off never does. */
    private boolean applyMadeHere(
            Client client, boolean on, SettingChangeSource source, UUID changedBy, ChangedByKind kind, String username,
            String reason) {
        if (on && !client.isSellsStock()) {
            throw new SellingNotAvailableException("Turn on selling first: things you make are sold the same way.");
        }
        if (client.isSellsMadeHere() == on) {
            return false;
        }
        client.setSellsMadeHere(on);
        clientRepository.save(client);
        changeRepository.save(ClientSettingChange.builder()
                .clientId(client.getId())
                .setting(ClientSettingChange.SELLS_MADE_HERE)
                .oldValue(Boolean.toString(!on))
                .newValue(Boolean.toString(on))
                .source(source)
                .changedBy(changedBy)
                .changedByKind(kind)
                .changedByUsername(username)
                .reason(reason)
                .changedAt(OffsetDateTime.now())
                .build());
        log.info("Selling things you make {} for client={} via {} by {}", on ? "on" : "off", client.getId(), source, changedBy);
        return true;
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
        // Support's switch is how a company joins the pilot, so it asks only whether the company
        // could sell at all; everyone else waits for the launch, or for support (Phase 7).
        boolean allowed = source == SettingChangeSource.SUPPORT
                ? sellingPolicy.couldSellStock(client)
                : sellingPolicy.canChooseToSellStock(client);
        if (!allowed) {
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
        ClientSettingChange madeHere = changeRepository
                .findFirstByClientIdAndSettingOrderByChangedAtDesc(client.getId(), ClientSettingChange.SELLS_MADE_HERE)
                .orElse(null);
        ClientSettingChange countsCash = changeRepository
                .findFirstByClientIdAndSettingOrderByChangedAtDesc(client.getId(), ClientSettingChange.COUNTS_CASH)
                .orElse(null);
        return new SellingSettingsResponse(
                client.isSellsStock(),
                sellingPolicy.canChooseToSellStock(client),
                last == null ? null : last.getChangedAt(),
                last == null ? null : changedByName(last),
                last == null ? null : last.getSource(),
                client.getStockUse(),
                productRepository.countByClientIdAndLegacyUnitPriceIsNotNullAndUnitPriceIsNullAndActiveTrue(client.getId()),
                client.getLegacyPricesDismissedAt() != null,
                client.isSellsMadeHere(),
                madeHere == null ? null : madeHere.getChangedAt(),
                madeHere == null ? null : changedByName(madeHere),
                client.isCountsCash(),
                countsCash == null ? null : countsCash.getChangedAt(),
                countsCash == null ? null : changedByName(countsCash),
                client.getCashReopenHours(),
                jdbc.queryForList("SELECT id FROM company_categories WHERE client_id = :id AND goes_with_meal",
                        new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("id", client.getId()), java.util.UUID.class));
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
        // Turning old prices into selling prices sets selling prices (V52, UX section 5.14).
        sellingPricePermission.require(actingUserId);
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
