package com.procurepal_services.stock_bridge_api.email;

import com.procurepal_services.stock_bridge_api.email.template.AccountEmails;
import com.procurepal_services.stock_bridge_api.email.verification.VerificationLink;
import com.procurepal_services.stock_bridge_api.email.template.OrderEmails;
import com.procurepal_services.stock_bridge_api.email.template.SettlementEmails;
import com.procurepal_services.stock_bridge_api.email.template.VendorEmails;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.OrderItem;
import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.entity.VendorWaitlistApplication;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.OrderItemRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The one door business code knocks on to send an email. Every method is
 * fire-and-forget, returns void, and cannot throw.
 *
 * <h2>What each method actually does</h2>
 * Resolves recipients, loads whatever the template needs, renders it, and hands the
 * finished {@link EmailMessage} to {@link EmailDispatcher}. All of that runs
 * synchronously, inside the caller's transaction - which is the point: entities are
 * still attached, lazy associations still resolve, and {@code TenantContext} is
 * still whatever the caller set. Only the SES call itself is deferred, to after the
 * commit and onto another thread. See {@link EmailDispatcher} for why the boundary
 * is drawn exactly there.
 *
 * <h2>Why it mirrors NotificationService instead of replacing it</h2>
 * These are two different channels with different guarantees, not one feature with
 * two backends. The bell is transactional, tenant-scoped, and cheap enough to fire
 * for every event; email is external, irrevocable, and rationed to the events a
 * person would want to be interrupted for. Merging them would force one set of
 * rules onto both, and the rules genuinely differ - most visibly in that a rollback
 * must erase a notification and cannot un-send an email.
 *
 * <p>They are called from the same places on purpose, so "what does the system tell
 * people when X happens" has one answer to read, in the service where X happens.
 */
@Service
@RequiredArgsConstructor
public class EmailNotificationService {

    private final EmailDispatcher dispatcher;
    private final EmailRecipients recipients;
    private final EmailProperties emailProperties;
    private final OrderItemRepository orderItemRepository;
    private final ClientRepository clientRepository;

    /** To ProcurePal: a company just placed an order. Pairs with NotificationType.NEW_ORDER. */
    public void newOrderPlaced(Order order) {
        dispatcher.dispatchQuietly(() -> OrderEmails.newOrderForOperator(
                recipients.forOperator(), order, itemsOf(order), buyerNameOf(order), baseUrl()));
    }

    /**
     * To the buyer: their receipt. Has no in-app counterpart, and should not - the
     * buyer is looking at the confirmation screen when this fires, so a bell entry
     * would tell them what they can already see. The email is for the inbox they
     * will search later.
     */
    public void orderConfirmedForBuyer(Order order) {
        dispatcher.dispatchQuietly(() -> OrderEmails.orderPlacedForBuyer(
                recipients.forOrderBuyer(order), order, itemsOf(order), baseUrl()));
    }

    /** To the buyer: ProcurePal moved the order along, or cancelled it. */
    public void orderStatusChanged(Order order, OrderStatus target, String note) {
        dispatcher.dispatchQuietly(() -> OrderEmails.orderStatusChangedForBuyer(
                recipients.forOrderBuyer(order), order, target, note, baseUrl()));
    }

    /** To both sides: a card payment verified. */
    public void paymentReceived(Order order) {
        dispatcher.dispatchQuietly(() -> OrderEmails.paymentReceivedForBuyer(
                recipients.forOrderBuyer(order), order, baseUrl()));
        dispatcher.dispatchQuietly(() -> OrderEmails.paymentReceivedForOperator(
                recipients.forOperator(), order, buyerNameOf(order), baseUrl()));
    }

    /** To the buyer: a card payment did not complete, and the order is still payable. */
    public void paymentFailed(Order order, String reason) {
        dispatcher.dispatchQuietly(() -> OrderEmails.paymentFailedForBuyer(
                recipients.forOrderBuyer(order), order, reason, baseUrl()));
    }

    /**
     * To a brand-new tenant's account holder, right after signup.
     *
     * <h2>Why this one message carries the verification link</h2>
     * {@code link} is nullable and is the token {@code EmailVerificationService}
     * just minted. Folding it in here rather than sending a second "please confirm
     * your address" mail is deliberate: a new account holder gets one message
     * instead of two near-identical ones seconds apart, the link that actually
     * unblocks their account is the primary call to action rather than competing
     * with a friendly greeting, and there is one delivery to fail instead of two.
     * {@code AccountEmails.welcome} says the same at more length.
     *
     * <p>Null means "no link could be issued" - no plausible address, or no
     * configured base URL - and renders exactly the email this sent before the
     * verification flow existed. It is never an error: this whole class is
     * fire-and-forget.
     */
    public void welcomeNewClient(Client client, User ownerUser, VerificationLink link) {
        dispatcher.dispatchQuietly(() -> AccountEmails.welcome(
                recipients.forUser(ownerUser), client.getName(), ownerUser.getUsername(), baseUrl(),
                urlOf(link), expiresInOf(link)));
    }

    /**
     * To a user an admin just created. {@code roleName} is passed rather than read
     * off the user because {@code User.role} is a lazy association, and while it
     * does resolve here (this runs in the caller's transaction), depending on that
     * would make this method quietly fragile to being called from anywhere else.
     */
    public void userInvited(User user, String roleName, VerificationLink link) {
        dispatcher.dispatchQuietly(() -> AccountEmails.userInvited(
                recipients.forUser(user), clientNameOf(user.getClientId()), user.getUsername(),
                roleName, baseUrl(), urlOf(link), expiresInOf(link)));
    }

    /**
     * To a user who asked for their confirmation link again - the standalone
     * verification email, with no welcome and no invitation wrapped around it.
     *
     * <p>Unlike the two above, {@code link} is required here: this message has
     * nothing else to say, so dispatching it without a link would send somebody a
     * mail whose entire subject is a button that is not there. Callers check first;
     * see {@code EmailVerificationService.resend}.
     *
     * <p>The address is read from the resolved recipient list rather than off the
     * user, so the email states exactly the address it was sent to. A resend is very
     * often triggered because the first one went somewhere wrong, and naming the
     * real destination is how a reader discovers a typo instead of waiting for mail
     * that will never arrive.
     */
    public void verifyEmailAddress(User user, VerificationLink link) {
        dispatcher.dispatchQuietly(() -> {
            List<String> to = recipients.forUser(user);
            return AccountEmails.verifyEmailAddress(
                    to, clientNameOf(user.getClientId()), to.isEmpty() ? "" : to.getFirst(),
                    urlOf(link), expiresInOf(link));
        });
    }

    /** To a user whose password an administrator just reset. */
    public void passwordResetByAdmin(User user) {
        dispatcher.dispatchQuietly(() -> AccountEmails.passwordChanged(
                recipients.forUser(user), user.getUsername(), clientNameOf(user.getClientId())));
    }

    /** To a tenant's admin contact when ProcurePal suspends or restores the account. */
    public void clientStatusChanged(Client client) {
        dispatcher.dispatchQuietly(() -> AccountEmails.accountStatusChanged(
                recipients.forClient(client.getId()), client.getName(), client.isActive(), baseUrl()));
    }

    // ------------------------------------------------------------------------
    // Vendor waitlist. Four messages, three of which go to somebody who has no
    // account and may never have one - see VendorEmails for why that decides
    // their EmailKind, and why getting it wrong means silence rather than an
    // error anybody would notice.
    // ------------------------------------------------------------------------

    /**
     * To ProcurePal: a business has applied to sell. One argument rather than the
     * nine fields the template needs, because this one is called from a public
     * endpoint where the entity has just been saved and is certainly attached - and
     * because a nine-argument call at the dispatch site is where a field gets
     * transposed with the one beside it.
     */
    public void vendorApplicationReceivedForOperator(VendorWaitlistApplication application) {
        dispatcher.dispatchQuietly(() -> VendorEmails.newApplicationForOperator(
                recipients.forVendorWaitlist(),
                application.getBusinessName(),
                application.getEmail(),
                application.getContactPhone(),
                application.getAddressLine1(),
                application.getAddressLine2(),
                application.getCity(),
                application.getState(),
                application.getNotes(),
                application.getCreatedAt(),
                baseUrl()));
    }

    /**
     * To the applicant: we have it.
     *
     * <p>Note what the recipient list is - {@code List.of(application.getEmail())},
     * built here rather than by an {@code EmailRecipients} method. That class
     * resolves audiences from rows the platform owns (a client's contact of record,
     * a user's address); an applicant's address is neither, it is a string a
     * stranger typed into a public form thirty seconds ago. Giving it a method
     * there would imply a lookup that does not exist. {@link EmailMessage}'s own
     * plausibility filter is what stops a malformed one reaching SES.
     */
    public void vendorApplicationReceivedForApplicant(VendorWaitlistApplication application) {
        dispatcher.dispatchQuietly(() -> VendorEmails.applicationReceived(
                List.of(application.getEmail()), application.getBusinessName()));
    }

    /**
     * To the applicant: approved, and here is the account.
     *
     * <p>{@code link} is nullable on exactly the terms {@link #welcomeNewClient}
     * describes - null means no link could be issued (no plausible address, no
     * configured base URL) and renders the approval without a confirm block rather
     * than failing.
     */
    public void vendorApplicationApproved(
            VendorWaitlistApplication application, Client vendor, User vendorUser, VerificationLink link) {
        dispatcher.dispatchQuietly(() -> VendorEmails.applicationApproved(
                List.of(application.getEmail()),
                vendor.getName(),
                vendorUser.getUsername(),
                application.getReviewNote(),
                baseUrl(),
                urlOf(link),
                expiresInOf(link)));
    }

    /** To the applicant: not this time, and the reviewer's note is the reason. */
    public void vendorApplicationRejected(VendorWaitlistApplication application) {
        dispatcher.dispatchQuietly(() -> VendorEmails.applicationRejected(
                List.of(application.getEmail()),
                application.getBusinessName(),
                application.getReviewNote()));
    }

    private List<OrderItem> itemsOf(Order order) {
        return orderItemRepository.findAllByOrderIdOrderByCreatedAtAsc(order.getId());
    }

    /**
     * The buyer's company name, for ProcurePal's copy of an order email. Falls back
     * to the same wording OrderLifecycleService uses for the bell, so the two
     * channels do not describe one deleted client differently.
     */
    /**
     * To every super admin: a super admin changed the vendor escrow hold (M9).
     *
     * <p>Fire-and-forget like everything else here, and that property is
     * load-bearing rather than incidental on this one: the caller has already
     * written the settings row and its audit row, and a failing email must not
     * unwind a money-policy change that has been made and recorded. See
     * {@code VendorSettlementSettingsService}, which additionally wraps this call -
     * belt and braces on top of this class's own never-throws contract, because the
     * cost of being wrong here is a rolled-back audit trail.
     *
     * <p>Recipients come from {@code EmailRecipients.forSuperAdmins()} INSIDE the
     * supplier, not outside it. That is the convention every method in this class
     * follows and it matters: {@code dispatchQuietly} only absorbs what happens
     * within the lambda, so a recipient lookup evaluated eagerly as an argument
     * would throw past it.
     *
     * @param changedByUsername the actor, already resolved by the caller - not read
     *     from a principal here, because this class is handed values rather than
     *     identities and because the caller has just written the same string onto
     *     the audit row it must agree with.
     */
    public void escrowHoldChanged(
            int previousHoldDays,
            int newHoldDays,
            String changedByUsername,
            OffsetDateTime changedAt,
            String reason,
            int payoutPeriodDays) {
        dispatcher.dispatchQuietly(() -> SettlementEmails.escrowHoldChanged(
                recipients.forSuperAdmins(),
                previousHoldDays,
                newHoldDays,
                changedByUsername,
                changedAt,
                reason,
                payoutPeriodDays,
                baseUrl()));
    }

    private String buyerNameOf(Order order) {
        return clientRepository.findById(order.getClientId())
                .map(Client::getName)
                .orElse("A customer");
    }

    private String clientNameOf(UUID clientId) {
        return clientId == null
                ? "your company"
                : clientRepository.findById(clientId).map(Client::getName).orElse("your company");
    }

    /**
     * Null-tolerant readers for the nullable {@link VerificationLink}, so the three
     * senders above stay one expression each and no template ever receives a
     * dereferenced null. Blank is what the templates already treat as "no confirm
     * block", so null and "unconfigured base URL" collapse to the same rendering.
     */
    private static String urlOf(VerificationLink link) {
        return link == null ? null : link.url();
    }

    private static String expiresInOf(VerificationLink link) {
        return link == null ? null : link.expiresIn();
    }

    private String baseUrl() {
        return emailProperties.normalizedAppBaseUrl();
    }
}
