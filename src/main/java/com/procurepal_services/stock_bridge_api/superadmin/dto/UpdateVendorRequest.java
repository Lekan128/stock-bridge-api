package com.procurepal_services.stock_bridge_api.superadmin.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * The body of PUT /api/superadmin/vendors/{id}.
 *
 * <h2>Replace semantics, and what that means for a nullable email</h2>
 * Every field here is assigned unconditionally, so a value the ops user cleared is
 * written as NULL rather than silently kept - the same rule
 * {@code SuperAdminClientService.update} and {@code CompanyService.update} both
 * follow, and it matters more here than usual: {@code email} is genuinely
 * clearable on a vendor (see {@code CreateVendorRequest}), so "absent means leave
 * alone" would make it the one field that cannot be emptied through the API that
 * is allowed to empty it.
 *
 * <h2>Deliberately no slug, and deliberately no credentials</h2>
 * {@code UpdateClientRequest} allows a rename because ops asked for it and the
 * cost is documented there at length; it is omitted here rather than duplicated,
 * because that endpoint already accepts a vendor's client id like any other and
 * two ways to rename the same row is one more than the number of places the
 * warning about what a rename breaks would get read.
 *
 * <p>Nothing here touches the vendor's user account either. Changing a username or
 * a password is a credential act on a customer's login, and this record edits
 * business metadata - a phone number, an address, a commission rate. Keeping them
 * apart is the same judgement {@code SuperAdminUserService} makes when it allows
 * broad reads and narrow writes.
 */
public record UpdateVendorRequest(
        @NotBlank @Size(max = 255) String name,
        /** Optional and clearable - see the class doc. */
        @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 50) String contactPhone,
        @Size(max = 255) String addressLine1,
        @Size(max = 255) String addressLine2,
        @Size(max = 100) String city,
        @Size(max = 100) String state,
        /** An S3 URL. TEXT in the schema, so length is deliberately unbounded here too. */
        String logoUrl,
        /** A fraction in 0..1, matching chk_clients_commission_rate_fraction. Null means none agreed. */
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal commissionRate) {
}
