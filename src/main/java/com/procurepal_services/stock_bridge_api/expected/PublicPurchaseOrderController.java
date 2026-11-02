package com.procurepal_services.stock_bridge_api.expected;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.expected.dto.PublicPurchaseOrderResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/public/purchase-orders/{token}} - what a supplier opens from the link in an email or a
 * WhatsApp message. No login: the unguessable token in the path is the whole of the access check.
 *
 * <p>HAZARD, as for every path in SecurityConfig's permit-all list: there is no principal, so the
 * tenant filter is off for the whole request. The service looks up by token alone and returns only
 * what is already printed on the PDF.
 */
@RestController
@RequestMapping("/api/public/purchase-orders")
@RequiredArgsConstructor
public class PublicPurchaseOrderController {

    private final PublicPurchaseOrderService service;

    @GetMapping("/{token}")
    public PublicPurchaseOrderResponse view(@PathVariable String token, HttpServletRequest request) {
        return service.view(token, request.getRemoteAddr());
    }

    /** {@code inline}: the browser opens its own PDF viewer, with print and download in its toolbar. */
    @GetMapping("/{token}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable String token, HttpServletRequest request) {
        PublicPurchaseOrderService.PdfFile file = service.pdf(token, request.getRemoteAddr());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(file.filename(), java.nio.charset.StandardCharsets.UTF_8)
                                .build().toString())
                // The link is a bearer secret: nothing in between may keep a copy.
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(file.bytes());
    }

    @ExceptionHandler(ExpectedDeliveryException.class)
    public ResponseEntity<ApiError> handle(ExpectedDeliveryException exception) {
        return ResponseEntity.status(exception.status()).body(new ApiError(exception.getMessage()));
    }
}
