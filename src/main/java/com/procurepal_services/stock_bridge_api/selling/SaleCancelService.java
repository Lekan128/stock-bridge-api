package com.procurepal_services.stock_bridge_api.selling;

import com.procurepal_services.stock_bridge_api.branch.BranchAccessService;
import com.procurepal_services.stock_bridge_api.branch.BranchStockService;
import com.procurepal_services.stock_bridge_api.entity.NotificationType;
import com.procurepal_services.stock_bridge_api.entity.StockMovement;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.notification.NotificationService;
import com.procurepal_services.stock_bridge_api.push.PushMessage;
import com.procurepal_services.stock_bridge_api.push.PushService;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import com.procurepal_services.stock_bridge_api.selling.dto.SaleResponse;
import com.procurepal_services.stock_bridge_api.settlement.PayoutCadence;
import com.procurepal_services.stock_bridge_api.stock.StockWritePermissionGuard;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cancelling a sale (selling till Step 3, owner 2026-11-16). A cancelled sale is never erased: its lines are
 * cancelled by compensating rows, and the sale says why, who cancelled it and - when a cashier had to ask -
 * who asked and who answered.
 *
 * <ul>
 *   <li>The cashier who recorded it, in its first two minutes: cancels it alone, with a reason.
 *   <li>Anyone with CANCEL_SALES: cancels any sale, any time, and approves or declines a request.
 *   <li>Otherwise the cashier asks; everyone who can cancel sales at that branch is told (bell and phone), and
 *       one of them answers from their own login - no PIN to share (owner, 2026-11-16).
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class SaleCancelService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

    private final SaleService saleService;
    private final SalePaymentService salePaymentService;
    private final StockWritePermissionGuard permissions;
    private final BranchAccessService branchAccessService;
    private final BranchStockService branchStockService;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final PushService pushService;

    /** Cancels the whole sale now - for whoever may (see the class). Cancelling a cancelled sale changes nothing. */
    @Transactional
    public SaleResponse cancel(UUID saleId, SaleCancelReason reason, String note, UUID actingUserId) {
        StockMovement first = saleService.linesOf(saleId).get(0);
        saleService.requireMayCancel(first, actingUserId);
        SalePaymentService.Header before = salePaymentService.header(requireTenantId(), saleId);
        saleService.cancel(saleId, null, actingUserId);
        salePaymentService.markCancelled(requireTenantId(), saleId, first.getBranchId(), reason, note, actingUserId);
        if ("REQUESTED".equals(before.cancelState()) && before.cancelRequestedBy() != null) {
            tellAsker(saleId, before.cancelRequestedBy(), actingUserId, true);
        }
        return saleService.get(saleId);
    }

    /**
     * A cashier asks for their own sale to be cancelled, past the first two minutes. Someone who may cancel it
     * now simply does. Asking again replaces the reason and tells the managers again.
     */
    @Transactional
    public SaleResponse request(UUID saleId, SaleCancelReason reason, String note, UUID actingUserId) {
        StockMovement first = saleService.linesOf(saleId).get(0);
        if (permissions.has(actingUserId, "CANCEL_SALES") || saleService.mayCancelAlone(first, actingUserId)) {
            return cancel(saleId, reason, note, actingUserId);
        }
        boolean own = first.getCreatedBy() != null && first.getCreatedBy().getId().equals(actingUserId);
        if (!own || !permissions.has(actingUserId, "STOCK_OUT")) {
            throw new AccessDeniedException("Only the person who recorded this sale can ask for it to be cancelled.");
        }
        SaleResponse sale = saleService.get(saleId);
        if ("CANCELLED".equals(sale.cancelState()) || sale.lineCount() == sale.cancelledLineCount()) {
            return sale;
        }
        salePaymentService.markRequested(requireTenantId(), saleId, first.getBranchId(), reason, note, actingUserId);
        tellManagers(saleService.get(saleId), first.getBranchId(), actingUserId);
        return saleService.get(saleId);
    }

    /** Someone who can cancel sales says yes: the whole sale is cancelled, in their name. */
    @Transactional
    public SaleResponse approve(UUID saleId, UUID actingUserId) {
        StockMovement first = saleService.linesOf(saleId).get(0);
        requireCanCancelSales(first, actingUserId);
        SalePaymentService.Header header = salePaymentService.header(requireTenantId(), saleId);
        if ("CANCELLED".equals(header.cancelState())) {
            return saleService.get(saleId);
        }
        if (!"REQUESTED".equals(header.cancelState())) {
            throw new SaleFixRefusedException("Nobody is waiting for this sale to be cancelled.");
        }
        saleService.cancelApproved(saleId, actingUserId);
        salePaymentService.markCancelled(requireTenantId(), saleId, first.getBranchId(), null, null, actingUserId);
        tellAsker(saleId, header.cancelRequestedBy(), actingUserId, true);
        return saleService.get(saleId);
    }

    /** ...or no: the sale stands, and the cashier is told. */
    @Transactional
    public SaleResponse decline(UUID saleId, UUID actingUserId) {
        StockMovement first = saleService.linesOf(saleId).get(0);
        requireCanCancelSales(first, actingUserId);
        SalePaymentService.Header header = salePaymentService.header(requireTenantId(), saleId);
        if (!"REQUESTED".equals(header.cancelState())) {
            if ("DECLINED".equals(header.cancelState())) {
                return saleService.get(saleId);
            }
            throw new SaleFixRefusedException("Nobody is waiting for this sale to be cancelled.");
        }
        salePaymentService.markDeclined(requireTenantId(), saleId, actingUserId);
        tellAsker(saleId, header.cancelRequestedBy(), actingUserId, false);
        return saleService.get(saleId);
    }

    // ------------------------------------------------------------------------------ rules ----

    private void requireCanCancelSales(StockMovement line, UUID actingUserId) {
        if (line.getBranchId() != null && branchStockService.isMultiBranch(line.getClientId())) {
            branchAccessService.requireMayRecordAt(actingUserId, line.getBranchId());
        }
        if (!permissions.has(actingUserId, "CANCEL_SALES")) {
            throw new AccessDeniedException("Only someone who can cancel sales can answer this.");
        }
    }

    // ------------------------------------------------------------------------------ telling ----

    /** To everyone who can cancel sales at that branch, but not the person asking: the bell and the phone. No email. */
    private void tellManagers(SaleResponse sale, UUID branchId, UUID askedBy) {
        UUID tenantId = requireTenantId();
        boolean multiBranch = branchStockService.isMultiBranch(tenantId);
        List<UUID> managers = userRepository.activeIdsWithPermission(tenantId, "CANCEL_SALES").stream()
                .filter(person -> !person.equals(askedBy))
                .filter(person -> !multiBranch || branchId == null || branchAccessService.mayRecordAt(person, branchId))
                .toList();
        if (managers.isEmpty()) {
            return;
        }
        String title = nameOf(askedBy) + " asks to cancel a sale";
        String body = summary(sale) + (sale.cancelReason() == null ? "" : " · Why: " + reasonText(sale.cancelReason()));
        String link = "/app/sales/" + sale.saleId();
        for (UUID person : managers) {
            notificationService.notifyClient(tenantId, person, NotificationType.SALE_CANCEL_WAITING, title, body, link, null);
        }
        pushService.notify(managers, new PushMessage(title, body, "sale-cancel-" + sale.saleId(), link, false));
    }

    private void tellAsker(UUID saleId, UUID asker, UUID doneBy, boolean cancelled) {
        if (asker == null || asker.equals(doneBy)) {
            return;
        }
        SaleResponse sale = saleService.get(saleId);
        String title = cancelled
                ? nameOf(doneBy) + " cancelled the sale you asked about"
                : nameOf(doneBy) + " kept the sale you asked to cancel";
        String link = "/app/sales/" + saleId;
        notificationService.notifyClient(requireTenantId(), asker, NotificationType.SALE_CANCEL_ANSWERED, title, summary(sale), link, null);
        pushService.notify(List.of(asker), new PushMessage(title, summary(sale), "sale-cancel-" + saleId, link, false));
    }

    /** "₦3,700 · Cash · 6:24 PM" */
    private static String summary(SaleResponse sale) {
        BigDecimal total = sale.total();
        StringBuilder text = new StringBuilder("₦").append(String.format(Locale.ENGLISH, "%,.0f", total));
        if (sale.paymentMethod() != null) {
            text.append(" · ").append(switch (sale.paymentMethod()) {
                case CASH -> "Cash";
                case TRANSFER -> "Transfer";
                case CARD -> "Card";
            });
        }
        if (sale.occurredAt() != null) {
            text.append(" · ").append(TIME.format(sale.occurredAt().atZoneSameInstant(PayoutCadence.ZONE)));
        }
        return text.toString();
    }

    static String reasonText(SaleCancelReason reason) {
        return switch (reason) {
            case WRONG_ITEM -> "Wrong item";
            case WRONG_AMOUNT -> "Wrong amount";
            case CHANGED_MIND -> "Customer changed mind";
            case OTHER -> "Something else";
        };
    }

    private String nameOf(UUID userId) {
        User user = userId == null ? null : userRepository.findById(userId).orElse(null);
        if (user == null) {
            return "Someone";
        }
        String full = ((user.getFirstName() == null ? "" : user.getFirstName().trim()) + " "
                + (user.getLastName() == null ? "" : user.getLastName().trim())).trim();
        return full.isEmpty() ? user.getUsername() : full;
    }

    private static UUID requireTenantId() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context set");
        }
        return tenantId;
    }
}
