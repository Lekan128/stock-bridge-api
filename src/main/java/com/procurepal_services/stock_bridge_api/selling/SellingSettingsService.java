package com.procurepal_services.stock_bridge_api.selling;

import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.ClientSettingChange;
import com.procurepal_services.stock_bridge_api.entity.ClientSettingChange.ChangedByKind;
import com.procurepal_services.stock_bridge_api.entity.NotificationType;
import com.procurepal_services.stock_bridge_api.entity.SettingChangeSource;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.notification.NotificationService;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.ClientSettingChangeRepository;
import com.procurepal_services.stock_bridge_api.repository.SuperAdminRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import com.procurepal_services.stock_bridge_api.selling.dto.SellingSettingsResponse;
import com.procurepal_services.stock_bridge_api.superadmin.ClientNotFoundException;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
        if (source == SettingChangeSource.SUPPORT) {
            throw new InvalidSettingSourceException();
        }
        Client client = currentClient();
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
                last == null ? null : last.getSource());
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
