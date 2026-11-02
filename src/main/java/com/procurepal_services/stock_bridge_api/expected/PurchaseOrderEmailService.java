package com.procurepal_services.stock_bridge_api.expected;

import com.procurepal_services.stock_bridge_api.email.EmailEligibility;
import com.procurepal_services.stock_bridge_api.email.EmailKind;
import com.procurepal_services.stock_bridge_api.email.EmailMessage;
import com.procurepal_services.stock_bridge_api.email.EmailSender;
import com.procurepal_services.stock_bridge_api.email.template.PurchaseOrderEmails;
import com.procurepal_services.stock_bridge_api.email.verification.VerifiedEmailGuard;
import com.procurepal_services.stock_bridge_api.entity.CompanyVendor;
import com.procurepal_services.stock_bridge_api.entity.PurchaseOrderEmail;
import com.procurepal_services.stock_bridge_api.entity.PurchaseOrderEmailStatus;
import com.procurepal_services.stock_bridge_api.repository.CompanyVendorRepository;
import com.procurepal_services.stock_bridge_api.repository.PurchaseOrderEmailRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Emails a purchase order to its supplier and writes down what happened
 * (PURCHASE_ORDER_PLAN.md phase 3, sections 4.3 and 4.7).
 *
 * <p>Every way it can fail to go is a row with a plain sentence, not an exception: the order page
 * and the screen right after creating read that sentence and show it as it is ("Confirm your email
 * address first", "Tony has no email saved"). The order itself always exists first - the email is
 * a courtesy attached to something that already happened.
 *
 * <p>Sent on the calling thread so the answer is known when the screen asks; one message of a
 * page or two is well inside what a request can carry. There is no scheduler anywhere in this
 * class: an email goes out because a person pressed a button, once, and again only if they press
 * it again.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PurchaseOrderEmailService {

    /** Emails a company may send in a rolling day. A wrong guess here costs the sending domain for everyone. */
    static final int DAILY_COMPANY_CAP = 50;

    /** Purchase orders any companies may send to one address in a rolling day. */
    static final int DAILY_ADDRESS_CAP = 10;

    private final ExpectedDeliveryService expectedDeliveryService;
    private final PurchaseOrderPdf purchaseOrderPdf;
    private final LogoFetcher logoFetcher;
    private final EmailSender emailSender;
    private final EmailEligibility emailEligibility;
    private final VerifiedEmailGuard verifiedEmailGuard;
    private final PurchaseOrderEmailRepository emailRepository;
    private final CompanyVendorRepository companyVendorRepository;
    private final UserRepository userRepository;
    private final TransactionTemplate transactions;

    /**
     * Emails the order to its supplier now. Always records an attempt; the caller reads it back off
     * the order. Never throws for "it could not be sent" - only for "there is no such order".
     */
    public void send(UUID orderId, UUID userId) {
        ExpectedDeliveryService.PurchaseOrderTarget target = expectedDeliveryService.purchaseOrderTarget(orderId, userId);
        PurchaseOrderSnapshot order = target.snapshot();
        String supplierName = order.supplier().name();

        CompanyVendor vendor = target.vendorId() == null
                ? null
                : companyVendorRepository.findById(target.vendorId()).orElse(null);
        // The supplier's address as it is now: if the buyer fixed a typo since the order was made,
        // the fix is what gets used. The PDF keeps the frozen copy.
        String address = firstNonBlank(vendor == null ? null : vendor.getEmail(), order.supplier().email());
        if (address != null) {
            address = address.trim().toLowerCase(Locale.ROOT);
        }

        if (target.calledOff()) {
            record(target, address, PurchaseOrderEmailStatus.FAILED, "This order was called off, so it isn't being sent.", null, userId);
            return;
        }
        if (!verifiedEmailGuard.isVerified(userId)) {
            record(target, address, PurchaseOrderEmailStatus.FAILED, "Confirm your email address first, then send this.", null, userId);
            return;
        }
        if (address == null) {
            record(target, null, PurchaseOrderEmailStatus.FAILED, supplierName + " has no email saved.", null, userId);
            return;
        }
        if (!emailEligibility.isEligible(address, EmailKind.SUPPLIER_DOCUMENT)) {
            record(target, address, PurchaseOrderEmailStatus.FAILED,
                    "That address can't receive email - it has bounced before. Check it and fix it.", null, userId);
            return;
        }
        OffsetDateTime since = OffsetDateTime.now().minusDays(1);
        if (emailRepository.countHandedOverSince(requireTenantId(), since) >= DAILY_COMPANY_CAP) {
            record(target, address, PurchaseOrderEmailStatus.FAILED,
                    "You've reached today's limit of " + DAILY_COMPANY_CAP
                            + " emailed orders. Send this one on WhatsApp, or open the PDF and send it yourself.",
                    null, userId);
            return;
        }
        if (emailRepository.countToAddressSince(address, since) >= DAILY_ADDRESS_CAP) {
            record(target, address, PurchaseOrderEmailStatus.FAILED,
                    "Too many orders have been emailed to that address today. Try again tomorrow, or use WhatsApp.",
                    null, userId);
            return;
        }

        byte[] pdf = purchaseOrderPdf.render(order, logoFetcher.fetch(order.buyer().logoUrl()));
        EmailMessage message = PurchaseOrderEmails.send(order, address, pdf, replyToFor(userId, order));
        EmailSender.SendResult result = emailSender.sendNow(message);

        if (!result.accepted()) {
            record(target, address, PurchaseOrderEmailStatus.FAILED, result.problem(), null, userId);
            return;
        }
        record(target, address, PurchaseOrderEmailStatus.SENT, null, result.messageId(), userId);
        if (vendor != null && !vendor.isEmailOrders()) {
            // It went through, so next time "email the order to Tony" starts on (plan 4.3a).
            UUID vendorId = vendor.getId();
            transactions.executeWithoutResult(status -> companyVendorRepository.findById(vendorId).ifPresent(found -> {
                found.setEmailOrders(true);
                companyVendorRepository.save(found);
            }));
        }
    }

    /** Where the supplier's reply should go: the person sending it, or failing that the company's contact. */
    private String replyToFor(UUID userId, PurchaseOrderSnapshot order) {
        String personal = userRepository.findById(userId).map(user -> user.getEmail()).orElse(null);
        return firstNonBlank(personal, order.buyer().email());
    }

    private void record(
            ExpectedDeliveryService.PurchaseOrderTarget target,
            String address,
            PurchaseOrderEmailStatus status,
            String problem,
            String providerMessageId,
            UUID userId) {
        transactions.executeWithoutResult(tx -> emailRepository.saveAndFlush(PurchaseOrderEmail.builder()
                .expectedDeliveryId(target.orderId())
                .revision(target.revision())
                .toAddress(address)
                .status(status)
                .problem(problem)
                .providerMessageId(providerMessageId)
                .createdBy(userId)
                .build()));
        if (status == PurchaseOrderEmailStatus.FAILED) {
            log.info("Purchase order {} was not emailed: {}", target.snapshot().poNumber(), problem);
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second != null && !second.isBlank() ? second.trim() : null;
    }

    private static UUID requireTenantId() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context set");
        }
        return tenantId;
    }
}
