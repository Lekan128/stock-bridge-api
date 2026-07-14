package com.procurepal_services.stock_bridge_api.client;

import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/clients")
@RequiredArgsConstructor
public class ClientSignupController {

    private final ClientSignupService clientSignupService;

    @PostMapping("/signup")
    public TenantLoginResponse signup(@Valid @RequestBody ClientSignupRequest request) {
        return clientSignupService.signup(request);
    }
}
