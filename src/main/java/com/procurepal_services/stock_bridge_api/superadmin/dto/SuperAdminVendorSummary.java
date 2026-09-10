package com.procurepal_services.stock_bridge_api.superadmin.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of GET /api/superadmin/vendors.
 *
 * <h2>Why this is not SuperAdminClientSummary</h2>
 * A vendor IS a {@code clients} row, so reusing that record was the obvious move
 * and it is wrong on both sides. It carries {@code adminEmail} with the
 * documented meaning "doubles as the admin's login username, because
 * ClientSignupService creates the first user with username == adminContactEmail" -
 * which is exactly what is NOT true of a vendor: a super admin types the username
 * separately and the email may be absent altogether. And it omits the two things
 * a vendor list is read for, the commission rate and whether the account came off
 * the waitlist or was recruited offline.
 *
 * <p>{@code userCount} is here for one reason: a vendor has exactly ONE user
 * account and cannot create staff, so this column is the cheapest possible check
 * that the rule is holding. Anything other than 1 on a live vendor is a bug
 * somebody should see.
 */
public record SuperAdminVendorSummary(
        UUID id,
        String name,
        String slug,
        boolean active,
        /** Nullable for a vendor - see Client.adminContactEmail and the V11 CHECK. */
        String email,
        String phone,
        /** The vendor's single login. Null only in a state that should not happen - see toSummary. */
        String username,
        /** Null unless a rate has been agreed. A fraction in 0..1, not a percentage. */
        BigDecimal commissionRate,
        long userCount,
        long productCount,
        /**
         * True when this vendor was created by approving a waitlist application. The
         * false case is a super admin adding a business they recruited offline, and
         * the distinction is worth a column: it is the difference between "they came
         * to us and we have their own words on file" and "we went to them".
         */
        boolean fromWaitlist,
        OffsetDateTime createdAt) {
}
