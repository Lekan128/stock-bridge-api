package com.procurepal_services.stock_bridge_api.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/** SuperAdminUserService's counterpart to {@link UpdateUserRequest} - see {@link PlatformOwnerCreateUserRequest}. */
public record PlatformOwnerUpdateUserRequest(
        String role,
        Boolean active,
        @Size(max = 100) String firstName,
        @Size(max = 100) String lastName,
        @Email @Size(max = 255) String email,
        @Size(max = 50) String phone,
        @Size(max = 100) String jobTitle) {
}
