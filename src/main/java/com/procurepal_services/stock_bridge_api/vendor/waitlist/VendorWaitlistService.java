package com.procurepal_services.stock_bridge_api.vendor.waitlist;

import com.procurepal_services.stock_bridge_api.email.EmailNotificationService;
import com.procurepal_services.stock_bridge_api.entity.VendorWaitlistApplication;
import com.procurepal_services.stock_bridge_api.entity.VendorWaitlistStatus;
import com.procurepal_services.stock_bridge_api.repository.VendorWaitlistApplicationRepository;
import com.procurepal_services.stock_bridge_api.vendor.waitlist.dto.VendorWaitlistApplicationRequest;
import com.procurepal_services.stock_bridge_api.vendor.waitlist.dto.VendorWaitlistApplicationResponse;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accepts a business's application to sell on the marketplace, from a public
 * page, from a caller who is nobody.
 *
 * <h2>What "no TenantContext" means for everything below</h2>
 * The endpoint in front of this is in {@code SecurityConfig.PERMIT_ALL_PATHS}, and
 * that list carries a HAZARD notice this class has to honour: with no
 * authenticated principal, {@code TenantResolutionFilter} leaves
 * {@code TenantContext} empty and the Hibernate tenant filter DISABLED for the
 * whole request. A handler behind such a path must not rely on the filter for
 * anything.
 *
 * <p>This one does not have to, and the reason is structural rather than
 * careful: {@link VendorWaitlistApplication} is not a tenant-scoped entity at all
 * (see its class doc and {@code V11__vendors.sql}), so there is no filter to be
 * missing. An applicant has no {@code clients} row to be scoped to - that is what
 * a waitlist is. Nothing else is read or written here, and in particular nothing
 * reaches a tenant-scoped table, which is the line a future edit must not cross.
 *
 * <h2>Abuse control, in order</h2>
 * This is an unauthenticated endpoint that inserts a row and sends two emails,
 * one of them to an address the caller chose. Unlimited, that is a mail cannon
 * with ProcurePaddy's verified sending identity on the envelope, and the damage
 * does not land on the abuser - complaints and bounces are scored against a
 * sending DOMAIN that every tenant shares. {@link VendorWaitlistRateLimiter} is
 * the same in-process mechanism {@code EmailVerificationRateLimiter} already
 * applies to the other endpoint with this shape; see it for what that does and
 * does not cover.
 *
 * <p>The limit is checked BEFORE the insert and before either email, on two keys:
 * the caller's source address and the submitted email. Both are consulted every
 * time, and the source-address slot is spent even when the email key is what
 * refuses. That asymmetry is deliberate and is the safe direction - the
 * alternative, releasing a slot on refusal, lets a caller probe the email budget
 * for free.
 *
 * <h2>Why the emails cannot roll back the insert</h2>
 * Two independent guarantees, and it is worth knowing both because either alone
 * would be enough and the pair is what makes it not worth thinking about again.
 *
 * <p>First, {@code EmailNotificationService} is fire-and-forget by construction:
 * every method routes through {@code EmailDispatcher.dispatchQuietly}, which
 * catches everything rendering can throw, and neither it nor
 * {@code EmailRecipients} opens a transaction of its own - which is exactly the
 * property {@code EmailRecipients}' Javadoc explains at length, because an
 * exception crossing a nested transaction boundary would mark this one
 * rollback-only and the commit would then fail with
 * {@code UnexpectedRollbackException}. A bad email address would become a lost
 * application.
 *
 * <p>Second, the send is not attempted until this transaction has already
 * committed. {@code EmailDispatcher} registers an {@code afterCommit}
 * synchronization, so SES is only ever told about an application that is durably
 * on the waitlist - and a rolled-back submission never acknowledges anybody.
 *
 * <h2>The response says the same thing every time</h2>
 * See {@code VendorWaitlistApplicationResponse}. A repeat application from an
 * address that has applied before is accepted, stored and acknowledged exactly
 * like a first one; there is no unique index on {@code email} and no pre-check
 * here, on purpose.
 */
@Service
@RequiredArgsConstructor
public class VendorWaitlistService {

    /**
     * Namespaces so a submitted email that reads like an address cannot spend an
     * IP's budget, and vice versa. Constants rather than inline literals because
     * the two strings only work if they never collide.
     */
    private static final String IP_KEY_PREFIX = "ip:";

    private static final String EMAIL_KEY_PREFIX = "email:";

    private final VendorWaitlistApplicationRepository applicationRepository;
    private final VendorWaitlistRateLimiter rateLimiter;
    private final EmailNotificationService emailNotificationService;

    /**
     * @param sourceAddress the caller's remote address, resolved by the controller.
     *     Never trusted for anything but rate limiting - it is not stored, not
     *     logged and not shown to a reviewer, because behind a load balancer it is
     *     frequently the load balancer.
     */
    @Transactional
    public VendorWaitlistApplicationResponse submit(
            VendorWaitlistApplicationRequest request, String sourceAddress) {
        String email = normalizeRequired(request.email()).toLowerCase(Locale.ROOT);

        requireSlot(IP_KEY_PREFIX + sourceAddress);
        requireSlot(EMAIL_KEY_PREFIX + email);

        VendorWaitlistApplication application = applicationRepository.save(VendorWaitlistApplication.builder()
                .businessName(normalizeRequired(request.businessName()))
                .email(email)
                .contactPhone(normalizeRequired(request.contactPhone()))
                .addressLine1(normalize(request.addressLine1()))
                .addressLine2(normalize(request.addressLine2()))
                .city(normalize(request.city()))
                .state(normalize(request.state()))
                .notes(normalize(request.notes()))
                // Explicit rather than left to the builder default, because the
                // chk_vendor_waitlist_pending_is_unreviewed CHECK ties this value to
                // three columns being null and a row that arrives here with any other
                // status is a bug worth failing on rather than a state to fall into.
                .status(VendorWaitlistStatus.PENDING)
                .build());

        // saved(), not save() plus a flush: createdAt is a @CreationTimestamp and the
        // operator email prints it, so the entity has to be the one Hibernate
        // populated. save() returns exactly that instance for a new row.
        emailNotificationService.vendorApplicationReceivedForOperator(application);
        emailNotificationService.vendorApplicationReceivedForApplicant(application);

        return VendorWaitlistApplicationResponse.accepted();
    }

    private void requireSlot(String key) {
        VendorWaitlistRateLimiter.Verdict verdict = rateLimiter.tryAcquire(key);
        if (!verdict.allowed()) {
            throw new VendorWaitlistThrottledException(verdict.retryAfter());
        }
    }

    /**
     * Trimmed. Bean Validation has already refused blank for these three, so this
     * is about the leading space somebody pasted rather than about emptiness -
     * without it, {@code " Acme Ltd"} and {@code "Acme Ltd"} are two different
     * businesses to a reviewer scanning the queue.
     */
    private static String normalizeRequired(String value) {
        return value.trim();
    }

    /** Blank is how a form says "empty"; the database should say NULL. */
    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
