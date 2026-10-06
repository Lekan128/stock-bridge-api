package com.procurepal_services.stock_bridge_api.onboarding;

import com.procurepal_services.stock_bridge_api.onboarding.dto.OnboardingStatus;
import com.procurepal_services.stock_bridge_api.onboarding.dto.ProductListFile;
import com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** The shop's own first week: its setup checklist, and "Send us your list" (step 5). */
@RestController
@RequestMapping("/api/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingQueries queries;
    private final ProductListService productLists;
    private final NamedParameterJdbcTemplate jdbc;

    @GetMapping
    @Transactional(readOnly = true)
    public OnboardingStatus status(@AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        UUID clientId = principal.getClientId();
        ShopActivity activity = queries.activity(clientId);
        List<String> setup = jdbc.queryForList(
                "SELECT status FROM setup_requests WHERE client_id = :clientId ORDER BY created_at DESC LIMIT 1",
                Map.of("clientId", clientId), String.class);
        List<Boolean> support = jdbc.queryForList(
                "SELECT u.is_active FROM users u WHERE u.client_id = :clientId AND u.username = :username",
                Map.of("clientId", clientId, "username", SupportAccessService.USERNAME), Boolean.class);
        OffsetDateTime createdAt = jdbc.queryForObject(
                "SELECT created_at FROM clients WHERE id = :clientId", Map.of("clientId", clientId), OffsetDateTime.class);
        List<ProductListFile> files = productLists.files(clientId);
        return new OnboardingStatus(
                activity.products(),
                activity.stockChanges(),
                activity.staff(),
                files.size(),
                files,
                setup.isEmpty() ? null : setup.getFirst(),
                support.isEmpty() ? null : (support.getFirst() ? "ON" : "OFF"),
                createdAt);
    }

    /** One file of the shop's product list. Owners and anyone who manages products may send it. */
    @PostMapping("/product-list")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('MANAGE_PRODUCTS')")
    public ProductListFile sendList(
            @RequestPart("file") MultipartFile file, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return productLists.upload(principal.getClientId(), principal.getUserId(), file);
    }
}
