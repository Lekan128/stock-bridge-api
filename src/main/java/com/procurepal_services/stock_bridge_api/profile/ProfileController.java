package com.procurepal_services.stock_bridge_api.profile;

import com.procurepal_services.stock_bridge_api.profile.dto.ChangePasswordRequest;
import com.procurepal_services.stock_bridge_api.profile.dto.ProfileResponse;
import com.procurepal_services.stock_bridge_api.profile.dto.UpdateProfileRequest;
import com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service profile, open to every authenticated tenant user with no
 * @PreAuthorize - the authorization here is structural rather than
 * permission-based: there is no id in any path, so the only account these
 * endpoints can reach is the caller's own. /api/users is where acting on
 * somebody else lives, and it requires MANAGE_USERS.
 *
 * This deliberately overlaps with the SelfServiceNotAllowedException rule in
 * UserManagementService rather than contradicting it: role and active status
 * still can't be self-edited anywhere, including here.
 */
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;

    @GetMapping
    public ProfileResponse me(@AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return profileService.get(principal.getUserId());
    }

    @PutMapping
    public ProfileResponse update(
            @Valid @RequestBody UpdateProfileRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return profileService.update(principal.getUserId(), request);
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        profileService.changePassword(principal.getUserId(), request);
        return ResponseEntity.noContent().build();
    }
}
