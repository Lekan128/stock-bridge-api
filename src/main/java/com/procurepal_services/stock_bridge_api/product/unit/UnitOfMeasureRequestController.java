package com.procurepal_services.stock_bridge_api.product.unit;

import com.procurepal_services.stock_bridge_api.product.unit.dto.AdditionalUnitOfMeasureRequest;
import com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Can't find your unit? Tell us" - the small escape hatch beside {@code
 * ProductController.unitsOfMeasure}'s fixed, curated list. See {@code
 * UnitOfMeasure}'s class doc for why that list stays closed rather than
 * accepting free text, and {@link UnitOfMeasureRequestService} for what a
 * tenant gets instead: a message to ProcurePal's support inbox, and a human
 * decides whether to extend the enum.
 *
 * <h2>Its own controller, not a method on ProductController</h2>
 * {@code ProductController} is authorized per-method between VIEW_PRODUCTS
 * (reading the catalog) and MANAGE_PRODUCTS (changing it), and this request
 * is neither - suggesting a missing unit does not require permission to
 * manage the product catalog, only to belong to the tenant that is asking.
 * Folding it into that controller would mean picking one of those two
 * authorities for a request that fits neither, or inventing a third just for
 * this. A small dedicated controller, on the model of {@code
 * EmailVerificationResendController}, says what it needs plainly instead:
 * any authenticated tenant user, full stop. Keeping it in its own class also
 * means this addition never has to touch {@code ProductController.java},
 * which other work is changing concurrently.
 *
 * <h2>No @PreAuthorize</h2>
 * Same reasoning as {@code NotificationController}: there is no coherent
 * permission for "may ask a question", and {@code SecurityConfig} already
 * requires every {@code /api/**} caller to be an authenticated tenant user
 * (company or vendor) before a request reaches here.
 *
 * <h2>202, not 201 or 204</h2>
 * Nothing is created that the caller can address back - there is no id, no
 * resource, nothing to {@code GET} - so 201 would owe a {@code Location}
 * header this feature has no resource to point at. 202 states the literal
 * truth: the request has been accepted and a human will read it, on the same
 * reasoning {@code VendorWaitlistController} gives for its own 202.
 */
@RestController
@RequestMapping("/api/products/unit-of-measure-requests")
@RequiredArgsConstructor
public class UnitOfMeasureRequestController {

    private final UnitOfMeasureRequestService unitOfMeasureRequestService;

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void submit(
            @Valid @RequestBody AdditionalUnitOfMeasureRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        unitOfMeasureRequestService.submit(request, principal.getUserId());
    }
}
