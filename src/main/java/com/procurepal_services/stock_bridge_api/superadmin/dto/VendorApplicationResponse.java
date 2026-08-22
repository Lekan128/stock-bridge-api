package com.procurepal_services.stock_bridge_api.superadmin.dto;

import com.procurepal_services.stock_bridge_api.entity.VendorWaitlistApplication;
import com.procurepal_services.stock_bridge_api.entity.VendorWaitlistStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of the vendor waitlist, as a super admin sees it.
 *
 * <h2>One shape, not a Summary/Detail pair</h2>
 * {@code SuperAdminClientSummary} and {@code SuperAdminClientDetail} are two
 * records because the detail one costs three extra aggregate queries per row and
 * serving those for a page of twenty would be twenty times the work for a list
 * nobody reads that way. Nothing here has that property: an application is
 * fourteen columns on one row, the reviewer's screen wants all of them (the notes
 * field is the thing they read first, and it is exactly the field a "summary"
 * would drop), and there is no join. Two records would be one record and a
 * subset, kept in step by hand.
 *
 * <h2>Every field the applicant sent, plus the decision</h2>
 * Nothing is withheld from a super admin here - they are the audience the row was
 * written for. Note what that implies for the public side: this record is only
 * ever produced behind {@code /api/superadmin/**}, and the public endpoint
 * answers with {@code VendorWaitlistApplicationResponse}, which shares not one
 * field with it. That is deliberate - see that class on why an anonymous caller
 * gets a constant sentence.
 */
public record VendorApplicationResponse(
        UUID id,
        String businessName,
        String email,
        String contactPhone,
        String addressLine1,
        String addressLine2,
        String city,
        String state,
        String notes,
        VendorWaitlistStatus status,
        String reviewNote,
        /** The super_admins row that decided, or null while PENDING. */
        UUID reviewedBy,
        OffsetDateTime reviewedAt,
        /** The clients row approval created. Non-null exactly when status is APPROVED. */
        UUID approvedClientId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static VendorApplicationResponse from(VendorWaitlistApplication application) {
        return new VendorApplicationResponse(
                application.getId(),
                application.getBusinessName(),
                application.getEmail(),
                application.getContactPhone(),
                application.getAddressLine1(),
                application.getAddressLine2(),
                application.getCity(),
                application.getState(),
                application.getNotes(),
                application.getStatus(),
                application.getReviewNote(),
                application.getReviewedBy(),
                application.getReviewedAt(),
                application.getApprovedClientId(),
                application.getCreatedAt(),
                application.getUpdatedAt());
    }
}
