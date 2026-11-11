package com.procurepal_services.stock_bridge_api.companyvendor.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Just a supplier's email address - for the places that need to add or fix that one field in the
 * middle of something else (a purchase order about to be emailed) without sending back, and so
 * being held to, every other field of the record. Replace-style updates are what made "add their
 * email" fail on a supplier whose phone happened to be blank.
 */
public record CompanyVendorEmailRequest(
        @NotBlank @Email @Size(max = 255) String email) {
}
