package com.procurepal_services.stock_bridge_api.companyvendor.dto;

import com.procurepal_services.stock_bridge_api.entity.CompanyVendor;
import com.procurepal_services.stock_bridge_api.entity.CompanyVendorKind;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of the directory, as the list and every write echo it back.
 *
 * <h2>editable is sent rather than inferred</h2>
 * The frontend could compute it from {@code kind == 'EXTERNAL'}, and would then
 * own a copy of a rule the server enforces. Sending
 * {@link CompanyVendor#isEditableByOwningCompany()} means the pencil icon and the
 * 409 the server would answer with can never disagree, and a future third kind
 * changes one place instead of two.
 *
 * <h2>name is always the stored snapshot, in every response</h2>
 * Including for VERIFIED rows, where the platform vendor's live name may since
 * have moved on. That is deliberate: the list is sorted, searched and paged on
 * this column (see the CompanyVendor javadoc for why it is a column at all), so
 * a list whose names came from somewhere else would sort by one string and
 * display another. The live name is reported separately, and only on the detail
 * read - see {@link CompanyVendorDetailResponse}.
 */
public record CompanyVendorResponse(
        UUID id,
        CompanyVendorKind kind,
        /* The seller's clients id for VERIFIED, null for EXTERNAL. Read-only everywhere. */
        UUID platformClientId,
        String name,
        String contactPhone,
        String email,
        String addressLine1,
        String addressLine2,
        String city,
        String state,
        String notes,
        boolean editable,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static CompanyVendorResponse from(CompanyVendor vendor) {
        return new CompanyVendorResponse(
                vendor.getId(),
                vendor.getVendorKind(),
                vendor.getPlatformClientId(),
                vendor.getName(),
                vendor.getContactPhone(),
                vendor.getEmail(),
                vendor.getAddressLine1(),
                vendor.getAddressLine2(),
                vendor.getCity(),
                vendor.getState(),
                vendor.getNotes(),
                vendor.isEditableByOwningCompany(),
                vendor.isActive(),
                vendor.getCreatedAt(),
                vendor.getUpdatedAt());
    }
}
