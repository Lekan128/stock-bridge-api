package com.procurepal_services.stock_bridge_api.companyvendor.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create/update payload for an EXTERNAL directory entry - a supplier the company
 * types in itself.
 *
 * <h2>There is no vendorKind field, and no platformClientId field</h2>
 * Not an oversight, and not something to "add later for symmetry". This record is
 * the only body the create and update endpoints accept, so a caller physically
 * cannot ask for a VERIFIED row or point one at a real seller's client id - the
 * two mistakes {@code chk_company_vendors_external_shape} and
 * {@code chk_company_vendors_not_self} exist to catch. A DTO that cannot express
 * the illegal request is a stronger guarantee than a service check that has to
 * remember to reject it, and it is the same reasoning
 * {@code UpdateCompanyRequest} uses to keep {@code is_platform_owner} out of
 * reach. VERIFIED rows are written by one place only, on a real purchase - see
 * {@code CompanyVendorLinkService}.
 *
 * <h2>Why contactPhone is required here but nullable in the column</h2>
 * The column serves both kinds, and a VERIFIED row legitimately has none: the
 * authoritative number for a platform vendor is their own {@code clients.phone}.
 * For a hand-typed supplier there is no such fallback, so a name with no way to
 * reach it is a note, not a directory entry. The database says the same thing in
 * {@code chk_company_vendors_external_shape}; this annotation is what turns it
 * into a field error on the form instead of a 500.
 *
 * <p>Max lengths mirror the column widths exactly, so an over-long value is a
 * field error rather than a truncation or a driver-level failure.
 *
 * <p>{@code state} is checked against the Nigerian state list in the service
 * rather than here, for the reason {@code DeliveryAddressRequest} gives: bean
 * validation cannot hold the list, and a select on the frontend is a convenience
 * rather than a constraint.
 */
public record CompanyVendorRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 50) String contactPhone,
        @Email @Size(max = 255) String email,
        @Size(max = 255) String addressLine1,
        @Size(max = 255) String addressLine2,
        @Size(max = 100) String city,
        @Size(max = 100) String state,
        @Size(max = 1000) String notes) {
}
