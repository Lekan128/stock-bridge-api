package com.procurepal_services.stock_bridge_api.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Every field optional/nullable - only the ones provided are changed. Partial
 * semantics matter here specifically because of the profile fields: an admin
 * sending only {"active": false} must not blank out the user's name and phone
 * number as a side effect. PUT /api/me is the surface that replaces a profile
 * wholesale.
 *
 * <p>{@code roleId} rather than a role name - see CreateUserRequest.
 */
public record UpdateUserRequest(
        UUID roleId,
        Boolean active,
        @Size(max = 100) String firstName,
        @Size(max = 100) String lastName,
        @Email @Size(max = 255) String email,
        @Size(max = 50) String phone,
        @Size(max = 100) String jobTitle) {
}
