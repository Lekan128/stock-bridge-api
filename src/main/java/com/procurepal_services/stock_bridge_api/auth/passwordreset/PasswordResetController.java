package com.procurepal_services.stock_bridge_api.auth.passwordreset;

import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.auth.passwordreset.dto.PasswordResetCheckRequest;
import com.procurepal_services.stock_bridge_api.auth.passwordreset.dto.PasswordResetCheckResponse;
import com.procurepal_services.stock_bridge_api.auth.passwordreset.dto.PasswordResetCompleteRequest;
import com.procurepal_services.stock_bridge_api.auth.passwordreset.dto.PasswordResetRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service password reset. All three are public (permit-all in SecurityConfig):
 * the caller has, by definition, no working password. What protects them is the
 * token for check and complete, and for request the facts that it reveals nothing
 * and is rate-limited. See {@link PasswordResetService}.
 */
@RestController
@RequestMapping("/api/auth/password-reset")
@RequiredArgsConstructor
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    /** 202 whether or not the address has an account - see the service. */
    @PostMapping("/request")
    public ResponseEntity<Void> request(
            @Valid @RequestBody PasswordResetRequest request, HttpServletRequest httpRequest) {
        passwordResetService.request(request.email(), httpRequest.getRemoteAddr());
        return ResponseEntity.accepted().build();
    }

    /** Which account the link is for; does not use the link up. 400 for any link that cannot be used. */
    @PostMapping("/check")
    public PasswordResetCheckResponse check(@Valid @RequestBody PasswordResetCheckRequest request) {
        return passwordResetService.check(request.token());
    }

    /** Sets the password and signs the person in: the same body as a login. */
    @PostMapping("/complete")
    public TenantLoginResponse complete(@Valid @RequestBody PasswordResetCompleteRequest request) {
        return passwordResetService.complete(request.token(), request.newPassword());
    }
}
