package com.procurepal_services.stock_bridge_api.vendor.waitlist;

import com.procurepal_services.stock_bridge_api.vendor.waitlist.dto.VendorWaitlistApplicationRequest;
import com.procurepal_services.stock_bridge_api.vendor.waitlist.dto.VendorWaitlistApplicationResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public vendor waitlist form. One endpoint, no authentication, registered in
 * {@code SecurityConfig.PERMIT_ALL_PATHS} exactly as {@code ClientSignupController}
 * is - and subject to the same HAZARD that list documents: no principal means no
 * {@code TenantContext} and no Hibernate tenant filter for the whole request. See
 * {@link VendorWaitlistService} for why this handler does not need one.
 *
 * <h2>202, not 200 or 201</h2>
 * Nothing the caller can address has been created from their point of view. A 201
 * would owe them a {@code Location} for a resource only super admins may read, and
 * inventing one would be the enumeration handle
 * {@code VendorWaitlistApplicationResponse} exists to withhold. 202 states the
 * literal truth: this has been accepted and a human will act on it later, which
 * is also precisely what the page and the acknowledgement email tell them.
 *
 * <h2>Why the remote address is read here rather than in the service</h2>
 * {@code HttpServletRequest} is a web concern and the service takes a plain
 * String, so the rate-limiting rule stays testable without a servlet. It is used
 * for one thing - a rate-limit key - and is deliberately not stored, logged or
 * shown to a reviewer: behind a load balancer this is frequently the load
 * balancer, which {@link VendorWaitlistRateLimiter} says more about.
 */
@RestController
@RequestMapping("/api/vendor-waitlist")
@RequiredArgsConstructor
public class VendorWaitlistController {

    private final VendorWaitlistService vendorWaitlistService;

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public VendorWaitlistApplicationResponse submit(
            @Valid @RequestBody VendorWaitlistApplicationRequest request, HttpServletRequest httpRequest) {
        return vendorWaitlistService.submit(request, httpRequest.getRemoteAddr());
    }
}
