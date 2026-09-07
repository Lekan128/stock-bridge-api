package com.procurepal_services.stock_bridge_api.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * SuperAdminUserService's counterpart to {@link CreateUserRequest}, kept as a separate type
 * rather than shared: the platform-owner admin UI has no access to GET /api/roles (it carries
 * the super-admin token audience, not a tenant one - see RoleController), so it can only ever
 * hand back one of the fixed system role NAMES from its own hardcoded {@code roles.ts}, never a
 * role id. ProcurePal's platform-owner tenant has no custom roles either, so name-based
 * resolution is exactly right here even though the tenant-facing surface has moved to ids.
 */
public record PlatformOwnerCreateUserRequest(
        @NotBlank String username,
        @NotBlank @Size(min = 8) String password,
        @NotBlank String role,
        @Size(max = 100) String firstName,
        @Size(max = 100) String lastName,
        @Email @Size(max = 255) String email,
        @Size(max = 50) String phone,
        @Size(max = 100) String jobTitle) {
}
