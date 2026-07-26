package com.procurepal_services.stock_bridge_api.profile.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * Replace semantics, unlike UpdateUserRequest's patch semantics: this is the
 * body of a PUT from a profile form that always renders every field, so a null
 * or blank field means the user cleared it and it is written as NULL. Without
 * that, there would be no way to remove a phone number you'd rather not share.
 *
 * Nothing here can change role, active, root, or username - that's the entire
 * point of a self-service surface being separate from /api/users.
 */
public record UpdateProfileRequest(
        @Size(max = 100) String firstName,
        @Size(max = 100) String lastName,
        @Email @Size(max = 255) String email,
        @Size(max = 50) String phone,
        @Size(max = 100) String jobTitle) {
}
