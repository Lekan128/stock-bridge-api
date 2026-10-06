package com.procurepal_services.stock_bridge_api.onboarding;

import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.onboarding.dto.FirstWeekReport;
import com.procurepal_services.stock_bridge_api.onboarding.dto.ProductListFile;
import com.procurepal_services.stock_bridge_api.security.SuperAdminPrincipal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The team's side of a shop's first week (step 5), for super admins: the first-week list, the
 * messages sent, the lists shops sent, and opening a shop's workspace as Procurepaddy support.
 */
@RestController
@RequestMapping("/api/superadmin")
@RequiredArgsConstructor
public class SuperAdminOnboardingController {

    private final FirstWeekService firstWeek;
    private final ProductListService productLists;
    private final SupportAccessService supportAccess;

    @GetMapping("/first-week")
    public FirstWeekReport firstWeek(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String search) {
        return firstWeek.report(days, page, size, search);
    }

    @PostMapping("/first-week/{clientId}/messages/{kind}")
    public ResponseEntity<Void> markSent(
            @PathVariable UUID clientId, @PathVariable String kind, @AuthenticationPrincipal SuperAdminPrincipal principal) {
        firstWeek.markSent(clientId, kind, principal.getSuperAdminId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/first-week/{clientId}/messages/{kind}")
    public ResponseEntity<Void> unmark(@PathVariable UUID clientId, @PathVariable String kind) {
        firstWeek.unmark(clientId, kind);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/clients/{clientId}/product-list")
    public List<ProductListFile> listFiles(@PathVariable UUID clientId) {
        return productLists.files(clientId);
    }

    @GetMapping("/clients/{clientId}/product-list/{fileId}")
    public ResponseEntity<byte[]> download(@PathVariable UUID clientId, @PathVariable UUID fileId) {
        ProductListService.StoredFile file = productLists.download(clientId, fileId);
        MediaType type;
        try {
            type = MediaType.parseMediaType(file.contentType());
        } catch (IllegalArgumentException e) {
            type = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(file.data());
    }

    /** Opens the shop's workspace as its Procurepaddy support account. See SupportAccessService. */
    @PostMapping("/clients/{clientId}/support-session")
    public TenantLoginResponse supportSession(
            @PathVariable UUID clientId, @AuthenticationPrincipal SuperAdminPrincipal principal) {
        return supportAccess.openSession(clientId, principal.getSuperAdminId());
    }
}
