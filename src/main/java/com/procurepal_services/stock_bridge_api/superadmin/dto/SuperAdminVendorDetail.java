package com.procurepal_services.stock_bridge_api.superadmin.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A single vendor, for the super-admin vendor detail screen and the response to
 * both creation paths.
 *
 * <h2>The user block is the point of this record</h2>
 * {@code username} is what makes the approval flow completable: a super admin who
 * has just approved somebody has to be able to tell them what to log in as, and a
 * response that omitted it would send them to a separate users endpoint to find
 * out what they themselves had just typed. It is the username only - never the
 * password, which this API does not return anywhere and does not put in an email
 * either (see {@code AccountEmails}' class doc for the rule and
 * {@code VendorEmails} for it applied here).
 *
 * <p>Nullable, because a vendor whose single user was somehow removed is a real
 * state to be able to look at rather than a 500. It should never be null in
 * practice - both creation paths create the user in the same transaction as the
 * client.
 *
 * <h2>Why the source application is a whole block rather than an id</h2>
 * "Where did this vendor come from" is the question this screen exists to answer
 * when something goes wrong, and an id alone means a second request to a different
 * endpoint to learn a business name and a date. The three fields here are the ones
 * a reviewer reads; the full application is still available at
 * /api/superadmin/vendor-waitlist/{id} for the notes.
 */
public record SuperAdminVendorDetail(
        UUID id,
        String name,
        String slug,
        boolean active,
        String email,
        String phone,
        String addressLine1,
        String addressLine2,
        String city,
        String state,
        String logoUrl,
        /** A fraction in 0..1 (the DB CHECK enforces the range), or null if none agreed. */
        BigDecimal commissionRate,
        /**
         * Where ProcurePaddy pays this seller out, and the registration number behind
         * the business. All four optional and all four null until somebody fills them
         * in - a vendor approved off the waitlist always arrives without them.
         */
        String bankName,
        String bankAccountNumber,
        String bankAccountName,
        String cacNumber,
        /** The vendor's single user, or null in the state that should not happen. */
        UUID userId,
        String username,
        long productCount,
        /** Null when this vendor was created directly rather than off the waitlist. */
        UUID applicationId,
        OffsetDateTime appliedAt,
        OffsetDateTime approvedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
