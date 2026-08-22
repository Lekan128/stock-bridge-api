package com.procurepal_services.stock_bridge_api.vendor.waitlist.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The public "apply to sell on ProcurePaddy" form. Four things are asked for and
 * three are required, which is the whole design: this is a lead, not an account,
 * and every extra required field is a business that gives up halfway.
 *
 * <h2>Why these and not more</h2>
 * A reviewer needs to be able to look the business up and then reach a human
 * about it. Business name answers the first; email and phone answer the second,
 * and both are required because a reviewer who can only email gets no answer from
 * a shopfront that lives on WhatsApp, and one who can only call has nowhere to
 * send the outcome. Everything else - CAC number, bank details, categories - is
 * KYC that belongs after somebody has decided the business is worth onboarding
 * (see VENDOR_RESEARCH.md section C item 3), and asking for it on a public form
 * is asking strangers to hand over documents to a platform that has not agreed to
 * work with them.
 *
 * <h2>Validation is the same vocabulary as ClientSignupRequest</h2>
 * Bean Validation annotations, reported through {@code ValidationErrors.describe}
 * as one sentence naming the field - so the React form maps a field error to a
 * field without the API needing a second error shape for this one endpoint. Sizes
 * match the columns in {@code V11__vendors.sql} exactly; a value that would be
 * truncated by the database must be refused by the form instead, or the applicant
 * is silently told something different from what was stored.
 *
 * <p>{@code contactPhone} is length-only, deliberately, for the reason
 * {@code ClientSignupRequest} gives about its own phone field: Nigerian numbers
 * arrive as 0803..., +234 803..., with spaces and with dashes, and rejecting a
 * sales lead over a phone format would be a self-inflicted wound. It is required
 * here where signup's is optional, because signup already has a verified route
 * back to the person and this does not.
 */
public record VendorWaitlistApplicationRequest(
        @NotBlank @Size(max = 255) String businessName,
        // Required and format-checked, unlike the phone above. This is the address
        // every reply travels down, including the approval that carries their
        // login - a typo here is an applicant who never hears from us again, and
        // @Email catches the ones a human would spot.
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 50) String contactPhone,
        // The address is optional in full: an applicant who leaves it blank should
        // still reach a reviewer, who can ask. Nigeria-only, same four columns and
        // the same names as clients and company_vendors.
        @Size(max = 255) String addressLine1,
        @Size(max = 255) String addressLine2,
        @Size(max = 100) String city,
        @Size(max = 100) String state,
        // "Tell us about your business" - free text, deliberately unparsed, and the
        // field a reviewer reads first.
        @Size(max = 1000) String notes) {
}
